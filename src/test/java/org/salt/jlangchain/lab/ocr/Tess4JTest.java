package org.salt.jlangchain.lab.ocr;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;

public class Tess4JTest {

    // Method to read and extract text from an image using Tesseract OCR
    private static void readImage() {
        // Set the path to the image file
        File imageFile = new File("/Users/chuanzhizhu/Desktop/WX20250214-010226@2x.png");

        // Create a Tesseract object for OCR processing
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath("/opt/homebrew/Cellar/tesseract/5.5.0/share/tessdata");  // Set the Tesseract data folder path
        tesseract.setLanguage("eng+chi_sim");  // Set the OCR language (English "eng", Simplified Chinese "chi_sim")

        try {
            // Perform OCR on the image and get the text result
            String result = tesseract.doOCR(imageFile);
            System.out.println(result);  // Output the recognized text
        } catch (TesseractException e) {
            // Handle any OCR exceptions that occur
            System.err.println("OCR failed: " + e.getMessage());
        }
    }

    // Method to read and extract text from a PDF using Tesseract OCR
    private static void readPdf() {
        String filePath = "/Users/chuanzhizhu/Desktop/1788758958499336192.pdf";

        // Create a Tesseract object for OCR processing
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath("/opt/homebrew/Cellar/tesseract/5.5.0/share/tessdata");  // Set the Tesseract data folder path
        tesseract.setLanguage("eng+chi_sim");  // Set the OCR language (English "eng", Simplified Chinese "chi_sim")

        try (PDDocument document = PDDocument.load(new File(filePath))) {
            int numberOfPages = document.getNumberOfPages();

            // Loop through each page of the PDF
            for (int i = 0; i < numberOfPages; i++) {
                PDPage page = document.getPage(i);

                // Extract text from the current page using PDFTextStripper
                PDFTextStripper textStripper = new PDFTextStripper();
                textStripper.setStartPage(i + 1);
                textStripper.setEndPage(i + 1);
                String pageText = textStripper.getText(document);
                System.out.println("Page " + (i + 1) + " Content: \n" + pageText + "\n");

                // Get the resources (like images) from the current page
                PDResources resources = page.getResources();
                for (COSName xObjectName : resources.getXObjectNames()) {
                    if (resources.isImageXObject(xObjectName)) {
                        PDImageXObject imageObject = (PDImageXObject) resources.getXObject(xObjectName);
                        BufferedImage bImage = imageObject.getImage();

                        // Save the image as PNG bytes and print its size
                        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            ImageIO.write(bImage, "png", baos);
                            byte[] imageBytes = baos.toByteArray();
                            System.out.println("Page " + (i + 1) + " Image size: " + imageBytes.length);
                        }

                        // Perform OCR on the image and print the result
                        String result = tesseract.doOCR(bImage);
                        System.out.println("Page " + (i + 1) + " Image Content: " + result);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Set the necessary system properties for Tesseract (library and data paths)
        System.setProperty("jna.library.path", "/opt/homebrew/Cellar/tesseract/5.5.0/lib");
        System.setProperty("TESSDATA_PREFIX", "/opt/homebrew/Cellar/tesseract/5.5.0/share/");

        // Call the methods to read images and PDFs
        readImage();
        readPdf();
    }
}
