package org.salt.jlangchain.lab.pdf;

import org.icepdf.core.exceptions.PDFException;
import org.icepdf.core.exceptions.PDFSecurityException;
import org.icepdf.core.pobjects.Document;
import org.icepdf.core.pobjects.graphics.text.LineText;
import org.icepdf.core.pobjects.graphics.text.PageText;
import org.icepdf.core.pobjects.graphics.text.WordText;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class ICEpdfTest {

    private static void readTextAndImage(String filePath) {
        try {
            // Load the PDF document
            Document document = new Document();
            document.setFile(filePath);

            // Get number of pages
            int numberOfPages = document.getNumberOfPages();

            // Traverse each page for extracting text and images
            for (int i = 0; i < numberOfPages; i++) {
                // Extracting text content
                StringBuilder pageContent = new StringBuilder();
                PageText page = document.getPageText(i);
                for (LineText line : page.getPageLines()) {
                    for (WordText text : line.getWords()) {
                        pageContent.append(text.getText());
                    }
                }
                System.out.println("Page " + (i + 1) + " Content: \n" + pageContent + "\n");

                // Extracting image (if available) from the page
                List<Image> imageList = document.getPageImages(i);
                for (Image image : imageList) {
                    System.out.println("Page " + (i + 1) + " image:");

                    // Convert Image to BufferedImage for manipulation
                    BufferedImage bufferedImage = toBufferedImage(image);
                    byte[] imageBytes = convertImageToByteArray(bufferedImage);

                    // Example of saving the image to a file
                    String imageFilePath = "page_" + i + "_image.png";
                    File outputImageFile = new File(imageFilePath);
                    ImageIO.write(bufferedImage, "PNG", outputImageFile);

                    System.out.println("Page " + (i + 1) + " Image saved to: " + imageFilePath);

                    // Print the image's byte array length (for debugging)
                    System.out.println("Image Binary Length: " + imageBytes.length);
                }
            }
        } catch (IOException | PDFException | PDFSecurityException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // Helper method to convert Image to BufferedImage
    private static BufferedImage toBufferedImage(Image img) {
        if (img instanceof BufferedImage) {
            return (BufferedImage) img;
        }
        BufferedImage bImage = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB);
        Graphics2D bGr = bImage.createGraphics();
        bGr.drawImage(img, 0, 0, null);
        bGr.dispose();
        return bImage;
    }

    // Helper method to convert BufferedImage to byte array (in PNG format)
    private static byte[] convertImageToByteArray(BufferedImage bufferedImage) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "PNG", baos);  // You can change the format to JPEG, etc.
        return baos.toByteArray();
    }

    public static void main(String[] args) {
        String inputPdfPath = "/Users/chuanzhizhu/Desktop/1780761576456134656.pdf";
        readTextAndImage(inputPdfPath);
    }
}