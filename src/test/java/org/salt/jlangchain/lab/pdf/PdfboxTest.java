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
import org.apache.pdfbox.text.PDFTextStripperByArea;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;

public class PdfboxTest {

    private static void readAllText(String filePath) {
        File file = new File(filePath);
        PDDocument document = null;
        try {
            document = PDDocument.load(file);
            PDFTextStripper stripper = new PDFTextStripper();
            // one page
            // stripper.setStartPage(1);
            // stripper.setEndPage(1);
            String text = stripper.getText(document);
            System.out.println(text);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void readEachInfo(String filePath) {
        try (PDDocument document = PDDocument.load(new File(filePath))) {
            int numberOfPages = document.getNumberOfPages();
            // Process each page one by one
            for (int i = 0; i < numberOfPages; i++) {
                PDPage page = document.getPage(i);
                System.out.println("====== Processing Page " + (i + 1) + " ======");

                // ----------------------------------
                // 1. Extract binary data of all images on the current page
                // ----------------------------------
                PDResources resources = page.getResources();
                int imageCount = 0;
                for (COSName xObjectName : resources.getXObjectNames()) {
                    if (resources.isImageXObject(xObjectName)) {
                        PDImageXObject imageObject = (PDImageXObject) resources.getXObject(xObjectName);
                        BufferedImage bImage = imageObject.getImage();
                        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            // Write the image to a ByteArrayOutputStream in PNG format
                            ImageIO.write(bImage, "png", baos);
                            byte[] imageBytes = baos.toByteArray();
                            imageCount++;
                            System.out.println("Page " + (i + 1) + " - Image " + imageCount +
                                    " binary data size: " + imageBytes.length + " bytes");
                        }
                    }
                }
                if (imageCount == 0) {
                    System.out.println("No images found on Page " + (i + 1) + ".");
                }

                // ------------------------------------------------
                // 2. Extract text from a specified region (simulate table extraction)
                // ------------------------------------------------
                // Note: Adjust the region coordinates (x, y, width, height) based on the actual PDF layout.
                PDFTextStripperByArea areaStripper = new PDFTextStripperByArea();
                Rectangle tableRegion = new Rectangle(50, 200, 500, 200);
                areaStripper.addRegion("tableRegion", tableRegion);
                areaStripper.extractRegions(page);
                String tableText = areaStripper.getTextForRegion("tableRegion");
                System.out.println("Page " + (i + 1) + " - Text in the table region:");
                System.out.println(tableText);

                // ----------------------------------
                // 3. Extract all text content from the current page
                // ----------------------------------
                PDFTextStripper textStripper = new PDFTextStripper();
                // PDFTextStripper page numbering starts at 1, so set start and end page as (i+1)
                textStripper.setStartPage(i + 1);
                textStripper.setEndPage(i + 1);
                String pageText = textStripper.getText(document);
                System.out.println("Page " + (i + 1) + " - Full page text:");
                System.out.println(pageText);

                System.out.println("============================================\n");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        String filePath = "/Users/zcz/Desktop/1780761576456134656.pdf";
        //readAllText(filePath);
        readEachInfo(filePath);
    }
}
