package com.engineeringplatform.console;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Minimal .xlsx reader/writer (V06-WORK-005) — no Apache POI dependency.
 *
 * xlsx is a ZIP of XML parts. We only need the FIRST worksheet and the shared
 * string table for the simple template contract:
 *   header row: column / field / type / label / required / primaryKey / unique
 *               / length / comment (+ optional searchable / listVisible /
 *               formVisible / detailVisible / dictionary)
 * Each following row = one field definition.
 */
public final class XlsxSupport {

    private XlsxSupport() {}

    /** Parse first worksheet into rows of string cells (shared strings resolved). */
    public static List<List<String>> parseRows(byte[] xlsx) throws IOException {
        List<String> sharedStrings = new ArrayList<>();
        String sheetXml = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("xl/sharedStrings.xml".equals(name)) {
                    sharedStrings = parseSharedStrings(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                } else if (name.matches("xl/worksheets/sheet\\d+\\.xml")) {
                    sheetXml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        if (sheetXml == null) throw new IOException("no worksheet found in xlsx");
        return parseSheet(sheetXml, sharedStrings);
    }

    private static List<String> parseSharedStrings(String xml) {
        List<String> out = new ArrayList<>();
        // <si><t>text</t></si> — capture all <t> contents in order
        int idx = 0;
        while (true) {
            int tStart = xml.indexOf("<t", idx);
            if (tStart < 0) break;
            int gt = xml.indexOf('>', tStart);
            int tEnd = xml.indexOf("</t>", gt);
            if (tEnd < 0) break;
            out.add(unescape(xml.substring(gt + 1, tEnd)));
            idx = tEnd + 4;
        }
        return out;
    }

    private static List<List<String>> parseSheet(String xml, List<String> shared) {
        List<List<String>> rows = new ArrayList<>();
        // split <row ...>...</row>
        int idx = 0;
        while (true) {
            int rowStart = xml.indexOf("<row", idx);
            if (rowStart < 0) break;
            int rowTagEnd = xml.indexOf('>', rowStart);
            int rowEnd = xml.indexOf("</row>", rowTagEnd);
            if (rowEnd < 0) break;
            String rowXml = xml.substring(rowTagEnd + 1, rowEnd);
            rows.add(parseRow(rowXml, shared));
            idx = rowEnd + 6;
        }
        return rows;
    }

    private static List<String> parseRow(String rowXml, List<String> shared) {
        // Cell reference (r="B2") determines column position — empty cells may be
        // omitted by writers (e.g. openpyxl), so we must not shift columns.
        int maxCol = 0;
        java.util.regex.Matcher rm = java.util.regex.Pattern.compile("<c r=\"([A-Z]+)").matcher(rowXml);
        while (rm.find()) {
            maxCol = Math.max(maxCol, colIndex(rm.group(1)));
        }
        List<String> cells = new ArrayList<>(java.util.Collections.nCopies(maxCol + 1, ""));
        int idx = 0;
        while (true) {
            int cStart = rowXml.indexOf("<c ", idx);
            if (cStart < 0) break;
            int openEnd = rowXml.indexOf('>', cStart);
            if (openEnd < 0) break;
            String openTag = rowXml.substring(cStart, openEnd + 1);
            int col = 0;
            java.util.regex.Matcher rmm = java.util.regex.Pattern.compile("r=\"([A-Z]+)").matcher(openTag);
            if (rmm.find()) col = colIndex(rmm.group(1));
            if (openTag.trim().endsWith("/>")) {
                // self-closing empty cell: <c r="F2" t="inlineStr" />
                if (col >= 0 && col < cells.size()) cells.set(col, "");
                idx = openEnd + 1;
                continue;
            }
            int cEnd = rowXml.indexOf("</c>", openEnd);
            if (cEnd < 0) break;
            String cell = rowXml.substring(cStart, cEnd);
            if (col >= 0 && col < cells.size()) {
                cells.set(col, cellValue(cell, shared));
            }
            idx = cEnd + 4;
        }
        return cells;
    }

    private static int colIndex(String letters) {
        int v = 0;
        for (char c : letters.toCharArray()) {
            v = v * 26 + (c - 'A' + 1);
        }
        return v - 1;
    }

    private static String cellValue(String cell, List<String> shared) {
        // inline string: <is><t>text</t></is>
        int isStart = cell.indexOf("<is>");
        if (isStart >= 0) {
            int tStart = cell.indexOf("<t>", isStart);
            if (tStart >= 0) {
                int tEnd = cell.indexOf("</t>", tStart);
                if (tEnd >= 0) {
                    return unescape(cell.substring(tStart + 3, tEnd));
                }
            }
        }
        boolean isShared = cell.contains("t=\"s\"");
        int vStart = cell.indexOf("<v>");
        if (vStart < 0) return "";
        int vEnd = cell.indexOf("</v>", vStart);
        if (vEnd < 0) return "";
        String raw = unescape(cell.substring(vStart + 3, vEnd));
        if (isShared) {
            try {
                int s = Integer.parseInt(raw);
                return s >= 0 && s < shared.size() ? shared.get(s) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return raw;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'");
    }

    /** Minimal xlsx writer for the example template (single sheet, inline strings). */
    public static byte[] writeTemplate(String[] headers) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "</Types>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"fields\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("xl/_rels/workbook.xml.rels"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "</Relationships>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
            sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            sb.append("<row r=\"1\">");
            for (int i = 0; i < headers.length; i++) {
                sb.append("<c r=\"").append((char) ('A' + i)).append("1\" t=\"inlineStr\"><is><t>")
                        .append(headers[i]).append("</t></is></c>");
            }
            sb.append("</row>");
            // one example row
            sb.append("<row r=\"2\">");
            String[] example = {"code", "code", "string", "Code", "true", "", "true", "50", "unique code"};
            for (int i = 0; i < example.length; i++) {
                sb.append("<c r=\"").append((char) ('A' + i)).append("2\" t=\"inlineStr\"><is><t>")
                        .append(example[i]).append("</t></is></c>");
            }
            sb.append("</row>");
            sb.append("</sheetData></worksheet>");
            zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }
}
