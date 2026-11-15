package cm.carnet.mobilemoney;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Écrit un classeur Excel (.xlsx) sans bibliothèque externe.
 * Gère : styles (police, fond, bordure, format de nombre, alignement), formules avec valeur pré-calculée,
 * cellules fusionnées, volets figés, tableaux Excel (filtres et lignes alternées), listes déroulantes,
 * mise en forme conditionnelle, couleur d'onglet et mise en page d'impression.
 * Valeurs de cellule : String, Number, Date, null (vide), {@link Cell} (valeur + style) ou formule ({@link #f}).
 * Classe Java pure, testable sur ordinateur.
 */
public final class XlsxWriter {

    // ---------- Styles ----------

    /** Style de cellule. Les couleurs sont au format "RRGGBB". */
    public static final class Style {
        boolean bold, italic, wrap, strike;
        double size = 11;
        String color, fill, numFmt, align, valign;
        /** Bordure fine sur les 4 côtés (couleur borderColor), ou seulement en bas (bottom). */
        boolean border, bottom;
        String borderColor = "D9D9D9";
        int indent;
        int id = -1;

        public Style bold() { bold = true; return this; }
        public Style italic() { italic = true; return this; }
        public Style strike() { strike = true; return this; }
        public Style wrap() { wrap = true; return this; }
        public Style size(double s) { size = s; return this; }
        public Style color(String c) { color = c; return this; }
        public Style fill(String c) { fill = c; return this; }
        public Style fmt(String f) { numFmt = f; return this; }
        public Style align(String a) { align = a; return this; }
        public Style top() { valign = "top"; return this; }
        public Style middle() { valign = "center"; return this; }
        public Style border() { border = true; return this; }
        public Style border(String c) { border = true; borderColor = c; return this; }
        public Style bottomLine(String c) { bottom = true; borderColor = c; return this; }
        public Style indent(int n) { indent = n; return this; }

        /** Copie modifiable du style. */
        public Style copy() {
            Style s = new Style();
            s.bold = bold; s.italic = italic; s.wrap = wrap; s.strike = strike; s.size = size; s.color = color; s.fill = fill;
            s.numFmt = numFmt; s.align = align; s.valign = valign; s.border = border; s.bottom = bottom; s.borderColor = borderColor;
            s.indent = indent;
            return s;
        }
    }

    /** Valeur avec un style explicite. */
    public static final class Cell {
        final Object v;
        final String formula;
        final Style s;

        Cell(Object v, String formula, Style s) { this.v = v; this.formula = formula; this.s = s; }
    }

    public static Cell c(Object v, Style s) { return new Cell(v, null, s); }

    /** Formule (sans le signe =) et sa valeur déjà calculée, affichée même si le lecteur ne recalcule pas. */
    public static Cell f(String formula, Object cached, Style s) { return new Cell(cached, formula, s); }

    // ---------- Feuilles ----------

    static final class Cf {
        String sqref, type, operator, formula;
        Style dxf;
        int dxfId;
    }

    static final class Dv {
        String sqref, list;
    }

    public static final class Sheet {
        final String name;
        final double[] widths;
        final List<Object[]> rows = new ArrayList<>();
        final Map<Integer, Double> heights = new LinkedHashMap<>();
        final List<String> merges = new ArrayList<>();
        final List<Cf> cfs = new ArrayList<>();
        final List<Dv> dvs = new ArrayList<>();
        int freezeRows, freezeCols;
        String tabColor;
        boolean landscape, fitWidth, gridlines = true;
        int printTitleRows;
        /** Tableau Excel : nom, ligne d'en-tête (0-based), nombre de colonnes ; null si aucun. */
        String tableName;
        int tableHeader, tableCols;
        boolean autoFilter;
        int tableId;

        Sheet(String name, double... widths) {
            this.name = name;
            this.widths = widths;
        }

        /** Ajoute une ligne et renvoie son numéro Excel (1-based). */
        public int row(Object... cells) {
            rows.add(cells);
            return rows.size();
        }

        /** Numéro Excel de la prochaine ligne. */
        public int next() { return rows.size() + 1; }

        public Sheet height(int excelRow, double h) { heights.put(excelRow, h); return this; }
        public Sheet merge(String ref) { merges.add(ref); return this; }
        public Sheet freeze(int rows, int cols) { freezeRows = rows; freezeCols = cols; return this; }
        public Sheet tab(String color) { tabColor = color; return this; }
        public Sheet landscape() { landscape = true; return this; }
        public Sheet fitWidth() { fitWidth = true; return this; }
        public Sheet noGridlines() { gridlines = false; return this; }
        public Sheet printTitles(int rows) { printTitleRows = rows; return this; }
        /** Filtre automatique sur toute la zone à partir de la première ligne (si pas de tableau). */
        public Sheet autoFilter() { autoFilter = true; return this; }

        /** Déclare un tableau Excel dont la ligne d'en-tête est la ligne Excel headerRow ; il s'étend jusqu'à la dernière ligne. */
        public Sheet table(String name, int headerRow, int cols) {
            tableName = name;
            tableHeader = headerRow - 1;
            tableCols = cols;
            return this;
        }

        /** Mise en forme si la formule (relative à la 1re cellule de la zone) est vraie. */
        public Sheet whenTrue(String sqref, String formula, Style dxf) {
            Cf c = new Cf();
            c.sqref = sqref; c.type = "expression"; c.formula = formula; c.dxf = dxf;
            cfs.add(c);
            return this;
        }

        /** Mise en forme selon une comparaison de valeur (operator : notEqual, equal, lessThan, greaterThan…). */
        public Sheet whenValue(String sqref, String operator, String formula, Style dxf) {
            Cf c = new Cf();
            c.sqref = sqref; c.type = "cellIs"; c.operator = operator; c.formula = formula; c.dxf = dxf;
            cfs.add(c);
            return this;
        }

        /** Liste déroulante de valeurs fixes. */
        public Sheet list(String sqref, String... values) {
            Dv d = new Dv();
            d.sqref = sqref;
            d.list = String.join(",", values);
            dvs.add(d);
            return this;
        }
    }

    private final List<Sheet> sheets = new ArrayList<>();
    private final List<Style> styles = new ArrayList<>();
    private final List<Style> dxfs = new ArrayList<>();
    private final Map<String, Integer> numFmts = new LinkedHashMap<>();

    /** Styles par défaut selon le type de valeur. */
    public final Style dateDefault = new Style().fmt("dd/mm/yyyy hh:mm");
    public final Style numberDefault = new Style().fmt("#,##0.##;-#,##0.##;0");

    public XlsxWriter() {
        styles.add(new Style()); // 0 : normal
        styles.get(0).id = 0;
    }

    public Sheet sheet(String name, double... widths) {
        Sheet s = new Sheet(name, widths);
        sheets.add(s);
        return s;
    }

    /** Date et heure -> numéro de série Excel, à l'heure locale du téléphone. */
    public static double serial(long millis) {
        long ms = millis + TimeZone.getDefault().getOffset(millis);
        return ms / 86400000.0 + 25569.0;
    }

    /** Jour seul (numéro de série entier), pour les filtres et les regroupements par jour. */
    public static double serialDay(long millis) { return Math.floor(serial(millis)); }

    /** Heure seule (fraction de jour). */
    public static double serialTime(long millis) {
        double s = serial(millis);
        return s - Math.floor(s);
    }

    // ---------- Écriture ----------

    public byte[] bytes() throws IOException {
        // Recense les styles utilisés avant d'écrire styles.xml.
        int tables = 0;
        List<String> sheetXml = new ArrayList<>();
        for (Sheet s : sheets) {
            if (s.tableName != null) s.tableId = ++tables;
            sheetXml.add(sheetXml(s));
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            StringBuilder ct = new StringBuilder(XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            for (int i = 0; i < sheets.size(); i++) {
                ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                if (sheets.get(i).tableName != null)
                    ct.append("<Override PartName=\"/xl/tables/table").append(sheets.get(i).tableId)
                            .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml\"/>");
            }
            ct.append("</Types>");
            put(z, "[Content_Types].xml", ct.toString());

            put(z, "_rels/.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");

            StringBuilder wb = new StringBuilder(XML + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>");
            StringBuilder rels = new StringBuilder(XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < sheets.size(); i++) {
                Sheet s = sheets.get(i);
                wb.append("<sheet name=\"").append(esc(s.name)).append("\" sheetId=\"").append(i + 1)
                        .append("\" r:id=\"rId").append(i + 1).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i + 1)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(i + 1).append(".xml\"/>");
                if (s.printTitleRows > 0)
                    names.append("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"").append(i).append("\">")
                            .append(esc(quote(s.name))).append("!$1:$").append(s.printTitleRows).append("</definedName>");
            }
            int stylesRel = sheets.size() + 1;
            rels.append("<Relationship Id=\"rId").append(stylesRel)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
            wb.append("</sheets>");
            if (names.length() > 0) wb.append("<definedNames>").append(names).append("</definedNames>");
            // Recalcul complet à l'ouverture : les formules suivent les corrections faites dans Excel.
            wb.append("<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>");
            rels.append("</Relationships>");
            put(z, "xl/workbook.xml", wb.toString());
            put(z, "xl/_rels/workbook.xml.rels", rels.toString());
            put(z, "xl/styles.xml", stylesXml());

            for (int i = 0; i < sheets.size(); i++) {
                Sheet s = sheets.get(i);
                put(z, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml.get(i));
                if (s.tableName != null) {
                    put(z, "xl/worksheets/_rels/sheet" + (i + 1) + ".xml.rels", XML
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/table\" Target=\"../tables/table"
                            + s.tableId + ".xml\"/></Relationships>");
                    put(z, "xl/tables/table" + s.tableId + ".xml", tableXml(s));
                }
            }
        }
        return bos.toByteArray();
    }

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

    private final Map<String, Integer> styleKeys = new LinkedHashMap<>();

    private int styleId(Style s) {
        if (s == null) return 0;
        if (s.id >= 0) return s.id;
        // Deux styles identiques partagent le même format, même s'ils ont été créés séparément.
        String key = s.bold + "|" + s.italic + "|" + s.wrap + "|" + s.strike + "|" + s.size + "|" + s.color + "|" + s.fill + "|" + s.numFmt
                + "|" + s.align + "|" + s.valign + "|" + s.border + "|" + s.bottom + "|" + s.borderColor + "|" + s.indent;
        Integer known = styleKeys.get(key);
        if (known != null) return s.id = known;
        if (s.numFmt != null && !numFmts.containsKey(s.numFmt)) numFmts.put(s.numFmt, 164 + numFmts.size());
        s.id = styles.size();
        styles.add(s);
        styleKeys.put(key, s.id);
        return s.id;
    }

    private int dxfId(Style s) {
        int i = dxfs.indexOf(s);
        if (i >= 0) return i;
        dxfs.add(s);
        return dxfs.size() - 1;
    }

    private String stylesXml() {
        StringBuilder fonts = new StringBuilder(), fills = new StringBuilder(), borders = new StringBuilder(), xfs = new StringBuilder();
        List<String> fontList = new ArrayList<>(), fillList = new ArrayList<>(), borderList = new ArrayList<>();
        fillList.add("<fill><patternFill patternType=\"none\"/></fill>");
        fillList.add("<fill><patternFill patternType=\"gray125\"/></fill>");
        for (Style s : styles) {
            String font = fontXml(s, false);
            if (!fontList.contains(font)) fontList.add(font);
            String fill = s.fill == null ? fillList.get(0)
                    : "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF" + s.fill + "\"/><bgColor indexed=\"64\"/></patternFill></fill>";
            if (!fillList.contains(fill)) fillList.add(fill);
            String border = borderXml(s);
            if (!borderList.contains(border)) borderList.add(border);
            int fmt = s.numFmt == null ? 0 : numFmts.get(s.numFmt);
            xfs.append("<xf numFmtId=\"").append(fmt).append("\" fontId=\"").append(fontList.indexOf(font))
                    .append("\" fillId=\"").append(fillList.indexOf(fill)).append("\" borderId=\"").append(borderList.indexOf(border))
                    .append("\" xfId=\"0\"").append(fmt > 0 ? " applyNumberFormat=\"1\"" : "").append(" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"");
            if (s.align != null || s.valign != null || s.wrap || s.indent > 0) {
                xfs.append(" applyAlignment=\"1\"><alignment");
                if (s.align != null) xfs.append(" horizontal=\"").append(s.align).append("\"");
                if (s.valign != null) xfs.append(" vertical=\"").append(s.valign).append("\"");
                if (s.wrap) xfs.append(" wrapText=\"1\"");
                if (s.indent > 0) xfs.append(" indent=\"").append(s.indent).append("\"");
                xfs.append("/></xf>");
            } else {
                xfs.append("/>");
            }
        }
        for (String x : fontList) fonts.append(x);
        for (String x : fillList) fills.append(x);
        for (String x : borderList) borders.append(x);

        StringBuilder fmts = new StringBuilder();
        for (Map.Entry<String, Integer> e : numFmts.entrySet())
            fmts.append("<numFmt numFmtId=\"").append(e.getValue()).append("\" formatCode=\"").append(esc(e.getKey())).append("\"/>");

        StringBuilder dx = new StringBuilder();
        for (Style s : dxfs) {
            dx.append("<dxf>").append(fontXml(s, true));
            if (s.fill != null) dx.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF").append(s.fill)
                    .append("\"/><bgColor rgb=\"FF").append(s.fill).append("\"/></patternFill></fill>");
            dx.append("</dxf>");
        }

        return XML + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + (numFmts.isEmpty() ? "" : "<numFmts count=\"" + numFmts.size() + "\">" + fmts + "</numFmts>")
                + "<fonts count=\"" + fontList.size() + "\">" + fonts + "</fonts>"
                + "<fills count=\"" + fillList.size() + "\">" + fills + "</fills>"
                + "<borders count=\"" + borderList.size() + "\">" + borders + "</borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"" + styles.size() + "\">" + xfs + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "<dxfs count=\"" + dxfs.size() + "\">" + dx + "</dxfs>"
                + "<tableStyles count=\"0\" defaultTableStyle=\"TableStyleMedium2\" defaultPivotStyle=\"PivotStyleLight16\"/>"
                + "</styleSheet>";
    }

    private static String fontXml(Style s, boolean dxf) {
        StringBuilder f = new StringBuilder("<font>");
        if (s.bold) f.append("<b/>");
        if (s.italic) f.append("<i/>");
        if (s.strike) f.append("<strike/>");
        if (!dxf) f.append("<sz val=\"").append(num(s.size)).append("\"/>");
        if (s.color != null) f.append("<color rgb=\"FF").append(s.color).append("\"/>");
        if (!dxf) f.append("<name val=\"Calibri\"/><family val=\"2\"/>");
        return f.append("</font>").toString();
    }

    private static String borderXml(Style s) {
        if (s.border) {
            String side = " style=\"thin\"><color rgb=\"FF" + s.borderColor + "\"/>";
            return "<border><left" + side + "</left><right" + side + "</right><top" + side + "</top><bottom" + side + "</bottom><diagonal/></border>";
        }
        if (s.bottom)
            return "<border><left/><right/><top/><bottom style=\"medium\"><color rgb=\"FF" + s.borderColor + "\"/></bottom><diagonal/></border>";
        return "<border><left/><right/><top/><bottom/><diagonal/></border>";
    }

    private String sheetXml(Sheet s) {
        int maxCols = s.widths == null ? 0 : s.widths.length;
        for (Object[] row : s.rows) maxCols = Math.max(maxCols, row.length);
        int lastRow = Math.max(1, s.rows.size());

        // Un tableau Excel doit avoir au moins une ligne de données.
        if (s.tableName != null && s.rows.size() <= s.tableHeader + 1) {
            s.rows.add(new Object[s.tableCols]);
            lastRow = s.rows.size();
        }

        StringBuilder x = new StringBuilder(XML + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        x.append("<sheetPr>");
        if (s.tabColor != null) x.append("<tabColor rgb=\"FF").append(s.tabColor).append("\"/>");
        if (s.fitWidth) x.append("<pageSetUpPr fitToPage=\"1\"/>");
        x.append("</sheetPr>");
        x.append("<dimension ref=\"A1:").append(col(Math.max(0, maxCols - 1))).append(lastRow).append("\"/>");

        x.append("<sheetViews><sheetView workbookViewId=\"0\"").append(s.gridlines ? "" : " showGridLines=\"0\"")
                .append(s == sheets.get(0) ? " tabSelected=\"1\"" : "").append(">");
        if (s.freezeRows > 0 || s.freezeCols > 0) {
            String tl = col(s.freezeCols) + (s.freezeRows + 1);
            String pane = s.freezeRows > 0 && s.freezeCols > 0 ? "bottomRight" : s.freezeRows > 0 ? "bottomLeft" : "topRight";
            x.append("<pane");
            if (s.freezeCols > 0) x.append(" xSplit=\"").append(s.freezeCols).append("\"");
            if (s.freezeRows > 0) x.append(" ySplit=\"").append(s.freezeRows).append("\"");
            x.append(" topLeftCell=\"").append(tl).append("\" activePane=\"").append(pane).append("\" state=\"frozen\"/>")
                    .append("<selection pane=\"").append(pane).append("\" activeCell=\"").append(tl).append("\" sqref=\"").append(tl).append("\"/>");
        }
        x.append("</sheetView></sheetViews>");
        x.append("<sheetFormatPr defaultRowHeight=\"15\"/>");

        if (s.widths != null && s.widths.length > 0) {
            x.append("<cols>");
            for (int c = 0; c < s.widths.length; c++)
                x.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(num(s.widths[c]))
                        .append("\" customWidth=\"1\"/>");
            x.append("</cols>");
        }

        x.append("<sheetData>");
        for (int r = 0; r < s.rows.size(); r++) {
            Object[] row = s.rows.get(r);
            x.append("<row r=\"").append(r + 1).append("\"");
            Double h = s.heights.get(r + 1);
            if (h != null) x.append(" ht=\"").append(num(h)).append("\" customHeight=\"1\"");
            x.append(">");
            for (int c = 0; c < row.length; c++) {
                Object v = row[c];
                if (v == null) continue;
                cellXml(x, col(c) + (r + 1), v);
            }
            x.append("</row>");
        }
        x.append("</sheetData>");

        if (s.tableName == null && s.autoFilter && s.rows.size() > 1)
            x.append("<autoFilter ref=\"A1:").append(col(maxCols - 1)).append(s.rows.size()).append("\"/>");

        if (!s.merges.isEmpty()) {
            x.append("<mergeCells count=\"").append(s.merges.size()).append("\">");
            for (String m : s.merges) x.append("<mergeCell ref=\"").append(m).append("\"/>");
            x.append("</mergeCells>");
        }

        int prio = 1;
        for (Cf c : s.cfs) {
            x.append("<conditionalFormatting sqref=\"").append(c.sqref).append("\"><cfRule type=\"").append(c.type)
                    .append("\" dxfId=\"").append(dxfId(c.dxf)).append("\" priority=\"").append(prio++).append("\"");
            if (c.operator != null) x.append(" operator=\"").append(c.operator).append("\"");
            x.append("><formula>").append(esc(c.formula)).append("</formula></cfRule></conditionalFormatting>");
        }

        if (!s.dvs.isEmpty()) {
            x.append("<dataValidations count=\"").append(s.dvs.size()).append("\">");
            for (Dv d : s.dvs)
                x.append("<dataValidation type=\"list\" allowBlank=\"1\" showInputMessage=\"1\" showErrorMessage=\"1\" sqref=\"")
                        .append(d.sqref).append("\"><formula1>").append(esc("\"" + d.list + "\"")).append("</formula1></dataValidation>");
            x.append("</dataValidations>");
        }

        x.append("<printOptions horizontalCentered=\"1\"/>");
        x.append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>");
        x.append("<pageSetup paperSize=\"9\"").append(s.landscape ? " orientation=\"landscape\"" : " orientation=\"portrait\"")
                .append(s.fitWidth ? " fitToWidth=\"1\" fitToHeight=\"0\"" : "").append("/>");
        x.append("<headerFooter><oddFooter>&amp;L").append(esc(s.name)).append("&amp;RPage &amp;P / &amp;N</oddFooter></headerFooter>");
        if (s.tableName != null) x.append("<tableParts count=\"1\"><tablePart r:id=\"rId1\"/></tableParts>");
        x.append("</worksheet>");
        return x.toString();
    }

    private void cellXml(StringBuilder x, String ref, Object v) {
        Style st = null;
        String formula = null;
        if (v instanceof Cell) {
            Cell c = (Cell) v;
            st = c.s;
            formula = c.formula;
            v = c.v;
        }
        if (v instanceof Date) {
            if (st == null) st = dateDefault;
            v = serial(((Date) v).getTime());
        } else if (v instanceof Number && st == null) {
            st = numberDefault;
        }
        int sid = styleId(st);
        String s = sid > 0 ? " s=\"" + sid + "\"" : "";
        if (formula != null) {
            String fx = "<f>" + esc(formula) + "</f>";
            if (v instanceof Number) x.append("<c r=\"").append(ref).append("\"").append(s).append(">").append(fx).append("<v>").append(num((Number) v)).append("</v></c>");
            else if (v != null) x.append("<c r=\"").append(ref).append("\"").append(s).append(" t=\"str\">").append(fx).append("<v>").append(esc(v.toString())).append("</v></c>");
            else x.append("<c r=\"").append(ref).append("\"").append(s).append(" t=\"str\">").append(fx).append("<v></v></c>");
        } else if (v instanceof Number) {
            x.append("<c r=\"").append(ref).append("\"").append(s).append("><v>").append(num((Number) v)).append("</v></c>");
        } else if (v != null) {
            x.append("<c r=\"").append(ref).append("\"").append(s).append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(esc(v.toString())).append("</t></is></c>");
        } else if (sid > 0) {
            x.append("<c r=\"").append(ref).append("\"").append(s).append("/>");
        }
    }

    private String tableXml(Sheet s) {
        Object[] head = s.rows.get(s.tableHeader);
        String ref = "A" + (s.tableHeader + 1) + ":" + col(s.tableCols - 1) + s.rows.size();
        StringBuilder x = new StringBuilder(XML + "<table xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" id=\"")
                .append(s.tableId).append("\" name=\"").append(s.tableName).append("\" displayName=\"").append(s.tableName)
                .append("\" ref=\"").append(ref).append("\" totalsRowShown=\"0\">")
                .append("<autoFilter ref=\"").append(ref).append("\"/>")
                .append("<tableColumns count=\"").append(s.tableCols).append("\">");
        for (int c = 0; c < s.tableCols; c++) {
            Object h = c < head.length ? head[c] : null;
            if (h instanceof Cell) h = ((Cell) h).v;
            String name = h == null ? "Colonne " + (c + 1) : h.toString();
            x.append("<tableColumn id=\"").append(c + 1).append("\" name=\"").append(esc(name)).append("\"/>");
        }
        x.append("</tableColumns><tableStyleInfo name=\"TableStyleLight9\" showFirstColumn=\"0\" showLastColumn=\"0\"")
                .append(" showRowStripes=\"1\" showColumnStripes=\"0\"/></table>");
        return x.toString();
    }

    // ---------- Utilitaires ----------

    /** Nom de feuille entre apostrophes, pour les formules. */
    public static String quote(String sheet) { return "'" + sheet.replace("'", "''") + "'"; }

    static String num(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double v = n.doubleValue();
            if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
            return String.valueOf(v);
        }
        return n.toString();
    }

    static String num(double v) { return num((Number) v); }

    public static String col(int i) {
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
