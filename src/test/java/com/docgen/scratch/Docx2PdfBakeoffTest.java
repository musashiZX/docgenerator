package com.docgen.scratch;

import org.docx4j.convert.out.pdf.viaXSLFO.Conversion;
import org.docx4j.convert.out.pdf.viaXSLFO.PdfSettings;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Scratch experiment: convert sample docx files to PDF via docx4j-export-fo
 * and time it, for comparison against LibreOffice headless. Not part of the
 * app's permanent test suite - safe to delete after the experiment.
 */
class Docx2PdfBakeoffTest {

    private static final Path DOCS_DIR = Paths.get("docs");
    private static final Path OUT_DIR = Paths.get(
            "..", "pdf2word", "pdf2word", "solution", "docx2pdf_test");

    @Test
    void convertSamplesToPdf() throws Exception {
        Files.createDirectories(OUT_DIR);
        String[] files = {
                "table-3x3.docx",
                "multi-run-paragraph.docx",
                "21260  GUANGDELI, Dried Beancurd Roll, 25x300g (2).docx",
                "XYZ-Training-and-Instruction-Program-version0.docx"
        };

        StringBuilder report = new StringBuilder();
        report.append("file,status,millis,error\n");

        for (String f : files) {
            Path src = DOCS_DIR.resolve(f);
            String outName = f.replace(".docx", "") + ".docx4j.pdf";
            Path outPath = OUT_DIR.resolve(outName);
            long start = System.currentTimeMillis();
            try {
                WordprocessingMLPackage pkg = WordprocessingMLPackage.load(src.toFile());
                Conversion conversion = new Conversion(pkg);
                try (FileOutputStream os = new FileOutputStream(outPath.toFile())) {
                    conversion.output(os, new PdfSettings());
                }
                long ms = System.currentTimeMillis() - start;
                report.append(f).append(",OK,").append(ms).append(",\n");
                System.out.println("OK  " + f + " -> " + outPath + " (" + ms + " ms)");
            } catch (Throwable t) {
                long ms = System.currentTimeMillis() - start;
                String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
                report.append(f).append(",FAIL,").append(ms).append(",\"")
                        .append(msg.replace("\"", "'").replace("\n", " ")).append("\"\n");
                System.out.println("FAIL " + f + " -> " + msg);
                t.printStackTrace();
            }
        }

        Files.writeString(OUT_DIR.resolve("docx4j_export_fo_results.csv"), report.toString());
    }
}
