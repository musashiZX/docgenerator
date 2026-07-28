package com.docgen.support;

import org.docx4j.jaxb.Context;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.BooleanDefaultTrue;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.RPr;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.TblGrid;
import org.docx4j.wml.TblGridCol;
import org.docx4j.wml.Tc;
import org.docx4j.wml.TcPr;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;

import java.math.BigInteger;
import java.nio.file.Path;

/** Builds minimal .docx files for tests and fixture generation. */
public final class FixtureFactory {

    private FixtureFactory() {}

    public static WordprocessingMLPackage singleParagraph(String text) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        ObjectFactory factory = Context.getWmlObjectFactory();

        P paragraph = factory.createP();
        R run = factory.createR();
        Text value = factory.createText();
        value.setValue(text);
        run.getContent().add(value);
        paragraph.getContent().add(run);
        main.addObject(paragraph);

        return pkg;
    }

    public static void writeSingleParagraph(Path path, String text) throws Exception {
        singleParagraph(text).save(path.toFile());
    }

    /** One paragraph per given text, in order. */
    public static WordprocessingMLPackage paragraphs(String... texts) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        ObjectFactory factory = Context.getWmlObjectFactory();
        for (String text : texts) {
            P paragraph = factory.createP();
            paragraph.getContent().add(runWithText(factory, text));
            main.addObject(paragraph);
        }
        return pkg;
    }

    public static void writeParagraphs(Path path, String... texts) throws Exception {
        paragraphs(texts).save(path.toFile());
    }

    public static WordprocessingMLPackage multiRunParagraph(String part1, String part2) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        ObjectFactory factory = Context.getWmlObjectFactory();

        P paragraph = factory.createP();
        paragraph.getContent().add(runWithText(factory, part1));
        paragraph.getContent().add(runWithText(factory, part2));
        main.addObject(paragraph);
        return pkg;
    }

    public static WordprocessingMLPackage table3x3() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        ObjectFactory factory = Context.getWmlObjectFactory();

        Tbl table = factory.createTbl();
        TblGrid grid = factory.createTblGrid();
        for (int c = 0; c < 3; c++) {
            TblGridCol col = factory.createTblGridCol();
            col.setW(BigInteger.valueOf(2000));
            grid.getGridCol().add(col);
        }
        table.getContent().add(grid);

        for (int r = 0; r < 3; r++) {
            Tr row = factory.createTr();
            for (int c = 0; c < 3; c++) {
                Tc cell = factory.createTc();
                P paragraph = factory.createP();
                paragraph.getContent().add(runWithText(factory, "R" + r + "C" + c));
                cell.getContent().add(paragraph);
                row.getContent().add(cell);
            }
            table.getContent().add(row);
        }

        main.addObject(table);
        return pkg;
    }

    public static void writeTable3x3(Path path) throws Exception {
        table3x3().save(path.toFile());
    }

    /** Paragraph with a bold first run and a plain second run. */
    public static WordprocessingMLPackage boldThenNormal(String boldText, String normalText) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        ObjectFactory factory = Context.getWmlObjectFactory();

        P paragraph = factory.createP();
        R boldRun = runWithText(factory, boldText);
        RPr rPr = factory.createRPr();
        BooleanDefaultTrue bold = factory.createBooleanDefaultTrue();
        rPr.setB(bold);
        boldRun.setRPr(rPr);
        paragraph.getContent().add(boldRun);
        paragraph.getContent().add(runWithText(factory, normalText));
        main.addObject(paragraph);
        return pkg;
    }

    public static void writeBoldThenNormal(Path path, String boldText, String normalText) throws Exception {
        boldThenNormal(boldText, normalText).save(path.toFile());
    }

    /**
     * Two-row form table: first cell empty, then a question and a yes/no value.
     * Models layout rows that are not a uniform dataframe.
     */
    public static WordprocessingMLPackage qaStyleTable() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        ObjectFactory factory = Context.getWmlObjectFactory();

        Tbl table = factory.createTbl();
        TblGrid grid = factory.createTblGrid();
        for (int c = 0; c < 3; c++) {
            TblGridCol col = factory.createTblGridCol();
            col.setW(BigInteger.valueOf(2000));
            grid.getGridCol().add(col);
        }
        table.getContent().add(grid);

        Tr header = factory.createTr();
        header.getContent().add(cell(factory, ""));
        header.getContent().add(cell(factory, "Question"));
        header.getContent().add(cell(factory, "Answer"));
        table.getContent().add(header);

        Tr organic = factory.createTr();
        organic.getContent().add(cell(factory, ""));
        organic.getContent().add(cell(factory, "Is this product organic?"));
        Tc answer = cell(factory, "No");
        // Mark as spanned so isDataframe() is false — clone-by-example must still work.
        TcPr tcPr = factory.createTcPr();
        org.docx4j.wml.TcPrInner.GridSpan span = factory.createTcPrInnerGridSpan();
        span.setVal(BigInteger.valueOf(2));
        tcPr.setGridSpan(span);
        answer.setTcPr(tcPr);
        organic.getContent().add(answer);
        table.getContent().add(organic);

        main.addObject(table);
        return pkg;
    }

    /**
     * Language-style row: CE Size split into many runs with character spacing
     * (PDF-converted layout). Used to assert row clone does not collapse runs
     * when the CE Size text is unchanged.
     */
    public static WordprocessingMLPackage multiRunCeSizeTable() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        ObjectFactory factory = Context.getWmlObjectFactory();

        Tbl table = factory.createTbl();
        TblGrid grid = factory.createTblGrid();
        for (int c = 0; c < 3; c++) {
            TblGridCol col = factory.createTblGridCol();
            col.setW(BigInteger.valueOf(2000));
            grid.getGridCol().add(col);
        }
        table.getContent().add(grid);

        Tr spanish = factory.createTr();
        spanish.getContent().add(cell(factory, "Spanish"));
        spanish.getContent().add(cell(factory, "Y"));
        Tc ce = factory.createTc();
        P ceParagraph = factory.createP();
        String[] parts = {
                "Minimum", " ", "1.2mm", " ", "high", "(", "In", " ", "regular script)"
        };
        int[] spacings = {-2, -3, -2, -3, -2, -5, -2, -1, -2};
        for (int i = 0; i < parts.length; i++) {
            ceParagraph.getContent().add(runWithSpacing(factory, parts[i], spacings[i]));
        }
        ce.getContent().add(ceParagraph);
        spanish.getContent().add(ce);
        table.getContent().add(spanish);

        main.addObject(table);
        return pkg;
    }

    private static R runWithSpacing(ObjectFactory factory, String text, int spacing) {
        R run = runWithText(factory, text);
        RPr rPr = factory.createRPr();
        org.docx4j.wml.CTSignedTwipsMeasure measure = factory.createCTSignedTwipsMeasure();
        measure.setVal(BigInteger.valueOf(spacing));
        rPr.setSpacing(measure);
        run.setRPr(rPr);
        return run;
    }

    private static Tc cell(ObjectFactory factory, String text) {
        Tc cell = factory.createTc();
        P paragraph = factory.createP();
        paragraph.getContent().add(runWithText(factory, text));
        cell.getContent().add(paragraph);
        return cell;
    }

    /** Paragraph with one run per text fragment (models PDF-converted spacing). */
    public static WordprocessingMLPackage paragraphWithRuns(String... runTexts) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        MainDocumentPart main = pkg.getMainDocumentPart();
        ObjectFactory factory = Context.getWmlObjectFactory();

        P paragraph = factory.createP();
        for (String text : runTexts) {
            paragraph.getContent().add(runWithText(factory, text));
        }
        main.addObject(paragraph);
        return pkg;
    }

    public static void writeParagraphWithRuns(Path path, String... runTexts) throws Exception {
        paragraphWithRuns(runTexts).save(path.toFile());
    }

    private static R runWithText(ObjectFactory factory, String text) {
        R run = factory.createR();
        Text value = factory.createText();
        value.setValue(text);
        value.setSpace("preserve");
        run.getContent().add(value);
        return run;
    }
}
