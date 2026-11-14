package cm.carnet.mobilemoney;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Écrit un classeur Excel (.xlsx) sans bibliothèque externe.
 * Cellules acceptées : String (texte), Number (nombre), Date (date et heure), null (vide).
 * Classe Java pure, testable sur ordinateur.
 */
public final class XlsxWriter {

    public static final class Sheet {
        final String name;
        final double[] widths;
        final List<Object[]> rows = new ArrayList<>();
        boolean header = true;

        public Sheet(String name, double... widths) {
            this.name = name;
            this.widths = widths;
        }

        public Sheet row(Object... cells) {
            rows.add(cells);
            return this;
        }

        /** Sans ligne d'en-tête figée (feuille de synthèse libre). */
        public Sheet noHeader() {
            header = false;
            return this;
        }
    }

    private final List<Sheet> sheets = new ArrayList<>();

    public Sheet sheet(String name, double... widths) {
        Sheet s = new Sheet(name, widths);
        sheets.add(s);
        return s;
    }

    public byte[] bytes() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            for (int i = 0; i < sheets.size(); i++)
                ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            ct.append("</Types>");
            put(z, "[Content_Types].xml", ct.toString());

            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");

            StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 0; i < sheets.size(); i++) {
                wb.append("<sheet name=\"").append(esc(sheets.get(i).name)).append("\" sheetId=\"").append(i + 1)
                        .append("\" r:id=\"rId").append(i + 1).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i + 1)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(i + 1).append(".xml\"/>");
            }
            int styles = sheets.size() + 1;
            rels.append("<Relationship Id=\"rId").append(styles)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
            wb.append("</sheets></workbook>");
            rels.append("</Relationships>");
            put(z, "xl/workbook.xml", wb.toString());
            put(z, "xl/_rels/workbook.xml.rels", rels.toString());

            // Styles : 0 normal, 1 en-tête gras sur fond vert clair, 2 date et heure, 3 nombre avec séparateur de milliers.
            put(z, "xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"dd/mm/yyyy hh:mm\"/><numFmt numFmtId=\"165\" formatCode=\"#,##0.##;-#,##0.##;0\"/></numFmts>"
                    + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                    + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE3F1E8\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"4\">"
                    + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                    + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                    + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                    + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");

            for (int i = 0; i < sheets.size(); i++) put(z, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
        }
        return bos.toByteArray();
    }

    private static String sheetXml(Sheet s) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        if (s.header && !s.rows.isEmpty())
            x.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        if (s.widths != null && s.widths.length > 0) {
            x.append("<cols>");
            for (int c = 0; c < s.widths.length; c++)
                x.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(s.widths[c]).append("\" customWidth=\"1\"/>");
            x.append("</cols>");
        }
        x.append("<sheetData>");
        int maxCols = 0;
        for (int r = 0; r < s.rows.size(); r++) {
            Object[] row = s.rows.get(r);
            maxCols = Math.max(maxCols, row.length);
            x.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < row.length; c++) {
                Object v = row[c];
                if (v == null) continue;
                String ref = col(c) + (r + 1);
                boolean head = s.header && r == 0;
                if (v instanceof Date) {
                    x.append("<c r=\"").append(ref).append("\" s=\"2\"><v>").append(serial((Date) v)).append("</v></c>");
                } else if (v instanceof Number) {
                    x.append("<c r=\"").append(ref).append("\" s=\"3\"><v>").append(num((Number) v)).append("</v></c>");
                } else {
                    String t = v.toString();
                    boolean bold = head || t.startsWith("**");
                    if (t.startsWith("**")) t = t.substring(2);
                    x.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(bold ? " s=\"1\"" : "")
                            .append("><is><t xml:space=\"preserve\">").append(esc(t)).append("</t></is></c>");
                }
            }
            x.append("</row>");
        }
        x.append("</sheetData>");
        if (s.header && s.rows.size() > 1 && maxCols > 0)
            x.append("<autoFilter ref=\"A1:").append(col(maxCols - 1)).append(s.rows.size()).append("\"/>");
        x.append("</worksheet>");
        return x.toString();
    }

    /** Date -> numéro de série Excel, à l'heure locale du téléphone. */
    static String serial(Date d) {
        long ms = d.getTime() + TimeZone.getDefault().getOffset(d.getTime());
        return String.valueOf(ms / 86400000.0 + 25569.0);
    }

    static String num(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double v = n.doubleValue();
            if (v == Math.rint(v)) return String.valueOf((long) v);
            return String.valueOf(v);
        }
        return n.toString();
    }

    static String col(int i) {
        StringBuilder s = new StringBuilder();
        i++;
        while (i > 0) {
            int m = (i - 1) % 26;
            s.insert(0, (char) ('A' + m));
            i = (i - 1) / 26;
        }
        return s.toString();
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') continue;
            switch (ch) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default: b.append(ch);
            }
        }
        return b.toString();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
