package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.BusinessEntityField;
import com.engineeringplatform.generator.contracts.ResolvedBusinessModule;

import java.util.List;
import java.util.Map;

/**
 * V07-WORK-002 — Relationship-aware backend rendering (field-level semantics).
 *
 * Renders the write-side semantics the V0.7 Contract adds on top of V0.6:
 *   - reference fields: target-exists validation through the target module's
 *     Port (no MyBatis leakage; generated Service depends only on the Port)
 *   - enum fields: Contract enum-values validation (String persistence)
 *   - money fields: BigDecimal mapping + DECIMAL(precision, scale) columns
 *
 * This renderer produces CODE FRAGMENTS consumed by GenericModuleGenerator's
 * existing source methods — it is not a second generator/executor.
 */
public final class RelationBackendRenderer {

    /**
     * Extra constructor dependencies (target module Ports) the Service needs
     * for reference validation. Key = port variable name, value = port class
     * (simple name). Determined purely from Contract reference fields.
     */
    public record PortDependency(String varName, String className, String entityName, String modPkg) {
    }

    /** Collect reference-field target Port dependencies for a module. */
    public List<PortDependency> referencePortDependencies(ResolvedBusinessModule module,
                                                          List<ResolvedBusinessModule> allModules) {
        java.util.ArrayList<PortDependency> deps = new java.util.ArrayList<>();
        java.util.LinkedHashMap<String, PortDependency> unique = new java.util.LinkedHashMap<>();
        for (BusinessEntityField f : module.entity().fields()) {
            if (GenericModuleGenerator.isSystemField(f)) continue;
            if (!"reference".equals(f.semantic())) continue;
            String target = String.valueOf(f.reference().get("target"));
            for (ResolvedBusinessModule m : allModules) {
                if (m.id().equals(target)) {
                    String entity = m.entity().name();
                    String className = entity + "Port";
                    String varName = GenericModuleGenerator.decapitalize(entity) + "Port";
                    String modPkg = m.id().replace("-", "");
                    unique.putIfAbsent(className, new PortDependency(varName, className, entity, modPkg));
                    break;
                }
            }
        }
        deps.addAll(unique.values());
        return deps;
    }

    /**
     * Render the reference-validation block for a single field in create/update
     * (target-exists check via the target Port). Empty when not a reference or
     * when the target Port is not among the injected dependencies (safe skip —
     * the Service cannot validate what it cannot reach).
     */
    public String referenceValidation(BusinessEntityField f, String entityUpper, String requestVar,
                                      List<PortDependency> refPorts) {
        if (f.semantic() == null || !"reference".equals(f.semantic())) {
            return "";
        }
        String target = String.valueOf(f.reference().get("target"));
        String portVar = null;
        for (PortDependency dep : refPorts) {
            if (dep.modPkg().equals(target.replace("-", ""))) {
                portVar = dep.varName();
                break;
            }
        }
        if (portVar == null) {
            return "";
        }
        String msg = target + " referenced by " + f.name() + " not found: \" + " + requestVar + "." + f.name() + "()";
        return "        if (" + requestVar + "." + f.name() + "() != null) {\n"
                + "            " + portVar + ".findById(" + requestVar + "." + f.name() + "())\n"
                + "                    .orElseThrow(() -> new PlatformException(ErrorCode.of(\""
                + entityUpper + "_" + GenericModuleGenerator.upper(f.name()) + "_REFERENCE_NOT_FOUND\", \""
                + msg + ")));\n        }\n";
    }

    /**
     * Render the enum-values validation block for a single field in
     * create/update. Empty when the field has no Contract enum values.
     */
    public String enumValidation(BusinessEntityField f, String entityUpper, String requestVar) {
        if (f.enumValues() == null || f.enumValues().isEmpty()) {
            return "";
        }
        StringBuilder values = new StringBuilder();
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < f.enumValues().size(); i++) {
            String v = String.valueOf(f.enumValues().get(i).get("value"));
            if (i > 0) {
                values.append(", ");
                labels.append(", ");
            }
            values.append('"').append(v).append('"');
            labels.append(v);
        }
        return "        if (" + requestVar + "." + f.name() + "() != null\n"
                + "                && !java.util.Set.of(" + values + ").contains(" + requestVar + "." + f.name() + "())) {\n"
                + "            throw new PlatformException(ErrorCode.of(\""
                + entityUpper + "_" + GenericModuleGenerator.upper(f.name()) + "_INVALID\", \""
                + f.name() + " must be one of [" + labels + "]: \" + " + requestVar + "." + f.name() + "()));\n"
                + "        }\n";
    }

    /** money fields map to BigDecimal (double/float forbidden). */
    public static boolean isMoney(BusinessEntityField f) {
        return "money".equals(f.type());
    }

    /** enum-typed fields persist as String. */
    public static boolean isEnumType(BusinessEntityField f) {
        return "enum".equals(f.type()) || "status".equals(f.type());
    }
}
