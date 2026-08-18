package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.BusinessEntityField;
import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolvedBusinessModule;
import com.engineeringplatform.generator.contracts.ResolvedRelation;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V06-WORK-001 / V07-WORK-001 — Generic Business Module Resolver.
 *
 * Reads the optional {@code business} section of module manifests referenced by
 * project.modules and produces structured {@link ResolvedBusinessModule} records
 * (entity/fields/features/enterprise/frontend/relations) that enter the EPM as
 * structured input for the Module Generator — not raw Maps.
 *
 * V0.7 (V07-WORK-001) additions:
 *  - Field Semantics V2: reference config, enum values, extended types
 *  - Structured relations (MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE;
 *    MANY_TO_MANY is schema-reserved but explicitly unsupported in V0.7)
 *  - Deterministic relation/reference validation; invalid contracts are
 *    reported as ERROR resolution errors (no silent fallback)
 *
 * Uses the existing moduleManifests channel (same as DependencyResolver); no
 * second Resolver.
 */
public final class BusinessModuleResolver {

    private static final Set<String> RELATION_TYPES =
            Set.of("MANY_TO_ONE", "ONE_TO_MANY", "ONE_TO_ONE", "MANY_TO_MANY");
    private static final Set<String> SUPPORTED_RELATION_TYPES =
            Set.of("MANY_TO_ONE", "ONE_TO_MANY", "ONE_TO_ONE");

    /**
     * Resolves business definitions for all explicit modules that declare one.
     * Modules without a business section are left untouched (platform modules).
     */
    public void resolve(ResolverInput input, IntermediateResolutionState.Builder state) {
        List<String> moduleIds = ReferenceResolver.extractIds(input.projectManifest().get("modules"));
        for (String id : moduleIds) {
            Map<String, Object> manifest = input.moduleManifests().get(id);
            if (manifest == null) {
                continue;
            }
            Object business = manifest.get("business");
            if (!(business instanceof Map<?, ?> businessMap)) {
                continue; // not a business module
            }
            ResolvedBusinessModule module = parseModule(id, manifest, asMap(business), input, state);
            if (module != null) {
                state.businessModule(module);
            }
        }
    }

    private ResolvedBusinessModule parseModule(String id, Map<String, Object> manifest,
                                               Map<String, Object> business,
                                               ResolverInput input,
                                               IntermediateResolutionState.Builder state) {
        Map<String, Object> moduleMeta = asMap(manifest.get("module"));
        String name = str(moduleMeta.get("name"), id);
        String version = moduleMeta.get("version") instanceof String v ? v : null;
        String table = str(business.get("table"), id);

        ResolvedBusinessModule.BusinessEntity entity = parseEntity(id, asMap(business.get("entity")), input, state);
        List<String> features = parseFeatures(business.get("features"));
        Map<String, Object> enterprise = asMap(business.get("enterprise"));
        Map<String, Object> frontend = asMap(business.get("frontend"));
        List<ResolvedRelation> relations = parseRelations(id, business, entity, input, state);

        return new ResolvedBusinessModule(id, name, version, table, entity, features, enterprise, frontend, relations);
    }

    private ResolvedBusinessModule.BusinessEntity parseEntity(String moduleId, Map<String, Object> entityMap,
                                                              ResolverInput input,
                                                              IntermediateResolutionState.Builder state) {
        String name = str(entityMap.get("name"), "Entity");
        List<BusinessEntityField> fields = new ArrayList<>();
        Object fieldsVal = entityMap.get("fields");
        if (fieldsVal instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> f = asMap(m);
                    String fieldName = str(f.get("name"), "");
                    String fieldType = str(f.get("type"), "string");
                    String semantic = str(f.get("semantic"), "none");
                    Map<String, Object> reference = parseReference(moduleId, fieldName, semantic, f, input, state);
                    List<Map<String, Object>> enumValues = parseEnumValues(moduleId, fieldName, fieldType, semantic, f, state);
                    fields.add(new BusinessEntityField(
                            fieldName,
                            fieldType,
                            bool(f.get("required")),
                            bool(f.get("unique")),
                            intOrNull(f.get("length")),
                            intOrNull(f.get("precision")),
                            intOrNull(f.get("scale")),
                            f.get("default"),
                            bool(f.get("primaryKey")),
                            str(f.get("comment"), null),
                            semantic,
                            str(f.get("dictionary"), null),
                            asMap(f.get("frontend")),
                            reference,
                            enumValues));
                }
            }
        }
        return new ResolvedBusinessModule.BusinessEntity(name, fields);
    }

    // ---- V0.7: reference config ----

    /**
     * semantic=reference requires a reference config with an existing target;
     * a reference config on any other semantic is an error.
     */
    private Map<String, Object> parseReference(String moduleId, String fieldName, String semantic,
                                               Map<String, Object> f,
                                               ResolverInput input,
                                               IntermediateResolutionState.Builder state) {
        Object ref = f.get("reference");
        boolean hasRef = ref instanceof Map<?, ?> && !((Map<?, ?>) ref).isEmpty();
        if ("reference".equals(semantic)) {
            if (!hasRef) {
                state.error(ResolutionError.of(
                        "REFERENCE_CONFIG_REQUIRED",
                        "field '" + fieldName + "' in module '" + moduleId
                                + "' declares semantic=reference but has no reference config",
                        "module-manifest", moduleId, fieldName));
                return Map.of();
            }
            Map<String, Object> refMap = asMap(ref);
            String target = str(refMap.get("target"), null);
            if (target == null || target.isBlank()) {
                state.error(ResolutionError.of(
                        "REFERENCE_TARGET_REQUIRED",
                        "reference config of field '" + fieldName + "' in module '" + moduleId
                                + "' requires a target module id",
                        "module-manifest", moduleId, fieldName));
            } else if (!input.moduleManifests().containsKey(target)) {
                state.error(ResolutionError.of(
                        "REFERENCE_TARGET_UNKNOWN",
                        "reference config of field '" + fieldName + "' in module '" + moduleId
                                + "' references unknown target module '" + target + "'",
                        "module-manifest", moduleId, fieldName));
            } else {
                validateReferenceFields(moduleId, fieldName, target, refMap, input, state);
            }
            return refMap;
        }
        if (hasRef) {
            state.error(ResolutionError.of(
                    "REFERENCE_CONFIG_ON_NON_REFERENCE",
                    "field '" + fieldName + "' in module '" + moduleId
                            + "' has a reference config but semantic is '" + semantic
                            + "' (reference config is only valid for semantic=reference)",
                    "module-manifest", moduleId, fieldName));
        }
        return Map.of();
    }

    /**
     * V07-WORK-001: valueField/labelField/searchFields must exist on the target
     * module. id is the implicit system primary key (always valid as valueField);
     * labelField is only validated when explicitly provided (default name may
     * not exist on every target). searchFields must all exist when provided.
     */
    private void validateReferenceFields(String moduleId, String fieldName, String target,
                                         Map<String, Object> refMap,
                                         ResolverInput input,
                                         IntermediateResolutionState.Builder state) {
        Set<String> targetFields = targetFieldNames(input, target);
        String valueField = str(refMap.get("valueField"), "id");
        if (!targetFields.contains(valueField) && !"id".equals(valueField)) {
            state.error(ResolutionError.of(
                    "REFERENCE_FIELD_UNKNOWN",
                    "reference config of field '" + fieldName + "' in module '" + moduleId
                            + "' valueField '" + valueField + "' does not exist on target module '" + target + "'",
                    "module-manifest", moduleId, fieldName));
        }
        Object labelVal = refMap.get("labelField");
        if (labelVal instanceof String labelField && !targetFields.contains(labelField)) {
            state.error(ResolutionError.of(
                    "REFERENCE_FIELD_UNKNOWN",
                    "reference config of field '" + fieldName + "' in module '" + moduleId
                            + "' labelField '" + labelField + "' does not exist on target module '" + target + "'",
                    "module-manifest", moduleId, fieldName));
        }
        Object searchVal = refMap.get("searchFields");
        if (searchVal instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !targetFields.contains(s)) {
                    state.error(ResolutionError.of(
                            "REFERENCE_FIELD_UNKNOWN",
                            "reference config of field '" + fieldName + "' in module '" + moduleId
                                    + "' searchField '" + s + "' does not exist on target module '" + target + "'",
                            "module-manifest", moduleId, fieldName));
                }
            }
        }
    }

    private Set<String> targetFieldNames(ResolverInput input, String targetModuleId) {
        Set<String> names = new HashSet<>();
        Map<String, Object> manifest = input.moduleManifests().get(targetModuleId);
        if (manifest == null) {
            return names;
        }
        Object business = manifest.get("business");
        if (!(business instanceof Map<?, ?> businessMap)) {
            return names;
        }
        Object entity = businessMap.get("entity");
        if (!(entity instanceof Map<?, ?> entityMap)) {
            return names;
        }
        Object fields = entityMap.get("fields");
        if (!(fields instanceof List<?> list)) {
            return names;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m && m.get("name") instanceof String s) {
                names.add(s);
            }
        }
        return names;
    }

    // ---- V0.7: enum values ----

    /**
     * type=enum or semantic=enum requires a non-empty enum.values list;
     * an enum config on any other type/semantic is an error.
     */
    private List<Map<String, Object>> parseEnumValues(String moduleId, String fieldName, String fieldType,
                                                      String semantic, Map<String, Object> f,
                                                      IntermediateResolutionState.Builder state) {
        Object enumVal = f.get("enum");
        boolean isEnum = "enum".equals(fieldType) || "enum".equals(semantic);
        if (!isEnum) {
            if (enumVal instanceof Map<?, ?> && !((Map<?, ?>) enumVal).isEmpty()) {
                state.error(ResolutionError.of(
                        "ENUM_CONFIG_ON_NON_ENUM",
                        "field '" + fieldName + "' in module '" + moduleId
                                + "' has an enum config but type/semantic is not enum",
                        "module-manifest", moduleId, fieldName));
            }
            return List.of();
        }
        if (!(enumVal instanceof Map<?, ?> enumMap)) {
            state.error(ResolutionError.of(
                    "ENUM_VALUES_REQUIRED",
                    "field '" + fieldName + "' in module '" + moduleId
                            + "' declares enum type/semantic but has no enum.values list",
                    "module-manifest", moduleId, fieldName));
            return List.of();
        }
        Object valuesVal = enumMap.get("values");
        if (!(valuesVal instanceof List<?> values)) {
            state.error(ResolutionError.of(
                    "ENUM_VALUES_REQUIRED",
                    "field '" + fieldName + "' in module '" + moduleId
                            + "' declares enum type/semantic but enum.values is missing or empty",
                    "module-manifest", moduleId, fieldName));
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> entry = asMap(m);
                String value = str(entry.get("value"), "");
                if (value.isEmpty()) {
                    state.error(ResolutionError.of(
                            "ENUM_VALUE_REQUIRED",
                            "field '" + fieldName + "' in module '" + moduleId
                                    + "' has an enum entry without a value",
                            "module-manifest", moduleId, fieldName));
                    continue;
                }
                if (!seen.add(value)) {
                    state.error(ResolutionError.of(
                            "ENUM_VALUE_DUPLICATE",
                            "field '" + fieldName + "' in module '" + moduleId
                                    + "' has duplicate enum value '" + value + "'",
                            "module-manifest", moduleId, fieldName));
                    continue;
                }
                out.add(entry);
            }
        }
        return out;
    }

    // ---- V0.7: relations ----

    /**
     * Parses and validates business.relations. Validation is deterministic:
     *  - name non-empty and unique within module
     *  - type supported (MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE);
     *    MANY_TO_MANY is explicitly unsupported in V0.7
     *  - target module exists
     *  - MANY_TO_ONE / ONE_TO_ONE: localField belongs to this module
     *  - ONE_TO_MANY: mappedBy exists on the target module
     * Invalid relations produce ERROR resolution errors (never silent fallback).
     */
    private List<ResolvedRelation> parseRelations(String moduleId, Map<String, Object> business,
                                                  ResolvedBusinessModule.BusinessEntity entity,
                                                  ResolverInput input,
                                                  IntermediateResolutionState.Builder state) {
        Object relationsVal = business.get("relations");
        if (!(relationsVal instanceof List<?> list)) {
            return List.of();
        }
        List<ResolvedRelation> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> localFields = entity.fields().stream()
                .map(BusinessEntityField::name)
                .collect(java.util.stream.Collectors.toSet());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                state.error(ResolutionError.of(
                        "RELATION_MALFORMED",
                        "module '" + moduleId + "' has a malformed relation entry",
                        "module-manifest", moduleId, null));
                continue;
            }
            Map<String, Object> r = asMap(m);
            String name = str(r.get("name"), "");
            String type = str(r.get("type"), "");
            String target = str(r.get("target"), "");
            String localField = str(r.get("localField"), null);
            String mappedBy = str(r.get("mappedBy"), null);
            String targetField = str(r.get("targetField"), "id");
            boolean required = bool(r.get("required"));
            boolean composition = bool(r.get("composition"));

            if (name.isEmpty()) {
                state.error(ResolutionError.of(
                        "RELATION_NAME_REQUIRED",
                        "module '" + moduleId + "' has a relation without a name",
                        "module-manifest", moduleId, null));
                continue;
            }
            if (!names.add(name)) {
                state.error(ResolutionError.of(
                        "RELATION_NAME_DUPLICATE",
                        "module '" + moduleId + "' declares duplicate relation name '" + name + "'",
                        "module-manifest", moduleId, name));
                continue;
            }
            if (!RELATION_TYPES.contains(type)) {
                state.error(ResolutionError.of(
                        "RELATION_TYPE_UNKNOWN",
                        "module '" + moduleId + "' relation '" + name + "' has unknown type '" + type + "'",
                        "module-manifest", moduleId, name));
                continue;
            }
            if ("MANY_TO_MANY".equals(type)) {
                state.error(ResolutionError.of(
                        "RELATION_TYPE_UNSUPPORTED",
                        "module '" + moduleId + "' relation '" + name
                                + "' uses MANY_TO_MANY which is reserved but explicitly unsupported in V0.7",
                        "module-manifest", moduleId, name));
                continue;
            }
            if (target.isEmpty() || !input.moduleManifests().containsKey(target)) {
                state.error(ResolutionError.of(
                        "RELATION_TARGET_UNKNOWN",
                        "module '" + moduleId + "' relation '" + name + "' references unknown target module '"
                                + target + "'",
                        "module-manifest", moduleId, name));
                continue;
            }
            if (("MANY_TO_ONE".equals(type) || "ONE_TO_ONE".equals(type))) {
                if (localField == null || !localFields.contains(localField)) {
                    state.error(ResolutionError.of(
                            "RELATION_LOCAL_FIELD_UNKNOWN",
                            "module '" + moduleId + "' relation '" + name + "' localField '"
                                    + localField + "' does not exist in module fields",
                            "module-manifest", moduleId, name));
                    continue;
                }
            }
            if ("ONE_TO_MANY".equals(type)) {
                if (mappedBy == null || !targetHasField(input, target, mappedBy)) {
                    state.error(ResolutionError.of(
                            "RELATION_MAPPED_BY_UNKNOWN",
                            "module '" + moduleId + "' relation '" + name + "' mappedBy '"
                                    + mappedBy + "' does not exist on target module '" + target + "'",
                            "module-manifest", moduleId, name));
                    continue;
                }
            }
            out.add(new ResolvedRelation(name, type, target, localField, mappedBy, targetField, required, composition));
        }
        return out;
    }

    private boolean targetHasField(ResolverInput input, String targetModuleId, String fieldName) {
        Map<String, Object> manifest = input.moduleManifests().get(targetModuleId);
        if (manifest == null) {
            return false;
        }
        Object business = manifest.get("business");
        if (!(business instanceof Map<?, ?> businessMap)) {
            return false;
        }
        Object entity = businessMap.get("entity");
        if (!(entity instanceof Map<?, ?> entityMap)) {
            return false;
        }
        Object fields = entityMap.get("fields");
        if (!(fields instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                if (fieldName.equals(String.valueOf(m.get("name")))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> parseFeatures(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object value, String defaultValue) {
        return value instanceof String s ? s : defaultValue;
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }
}
