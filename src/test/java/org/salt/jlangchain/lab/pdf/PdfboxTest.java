/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.salt.jlangchain.lab.pdf;

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
import java.io.FileOutputStream;

public class PdfboxTest {

    // Method to read text and images from a PDF file
    private static void readTextAndImage(String filePath) {
        try (PDDocument document = PDDocument.load(new File(filePath))) {
            int numberOfPages = document.getNumberOfPages();

            // Loop through all pages in the PDF
            for (int i = 0; i < numberOfPages; i++) {
                PDPage page = document.getPage(i);

                // Extract text from the current page using PDFTextStripper
                PDFTextStripper textStripper = new PDFTextStripper();
                textStripper.setStartPage(i + 1);
                textStripper.setEndPage(i + 1);
                String pageText = textStripper.getText(document);
                System.out.println("Page " + (i + 1) + " Content: \n" + pageText + "\n");

                // Extract and save images from the current page
                PDResources resources = page.getResources();

                // Loop through all XObjects (objects in the page) to find images
                for (COSName xObjectName : resources.getXObjectNames()) {
                    if (resources.isImageXObject(xObjectName)) {
                        PDImageXObject imageObject = (PDImageXObject) resources.getXObject(xObjectName);
                        BufferedImage bImage = imageObject.getImage();

                        // Save the image as a PNG file
                        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            ImageIO.write(bImage, "png", baos);
                            byte[] imageBytes = baos.toByteArray();

                            // Create a unique filename for the image based on the current timestamp
                            String imageFilePath = "image_" + System.currentTimeMillis() + ".png";

                            // Write the image bytes to the file
                            try (FileOutputStream fos = new FileOutputStream(imageFilePath)) {
                                fos.write(imageBytes);
                                System.out.println("Page " + (i + 1) + " Image saved: " + imageFilePath);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        String filePath = "/Users/chuanzhizhu/Desktop/1780761576456134656.pdf";
        readTextAndImage(filePath);
    }
}

