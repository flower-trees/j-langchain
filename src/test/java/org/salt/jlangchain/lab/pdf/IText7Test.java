package org.salt.jlangchain.lab.pdf;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData;
import com.itextpdf.kernel.pdf.canvas.parser.data.ImageRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener;
import com.itextpdf.kernel.pdf.canvas.parser.listener.ITextExtractionStrategy;
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy;
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Set;

public class IText7Test {

    // Method to read text and images from a PDF file using iText 7
    private static void readTextAndImage(String filePath) {
        try {
            // Use iText 7's PdfReader to load the PDF file
            PdfDocument pdfDoc = new PdfDocument(new PdfReader(filePath));

            // Get the number of pages in the PDF
            int numberOfPages = pdfDoc.getNumberOfPages();

            // Loop through each page and extract text
            for (int i = 1; i <= numberOfPages; i++) {
                // Create a text extraction strategy
                ITextExtractionStrategy strategy = new SimpleTextExtractionStrategy();

                // Extract text from the current page
                String pageContent = PdfTextExtractor.getTextFromPage(pdfDoc.getPage(i), strategy);

                // Output the extracted text for the current page
                System.out.println("Page " + i + " Content: \n" + pageContent + "\n");

                // Use PdfCanvasProcessor to process page content and extract images
                PdfCanvasProcessor processor = new PdfCanvasProcessor(new ImageExtractionListener(i));
                processor.processPageContent(pdfDoc.getPage(i));
            }

            pdfDoc.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Define a listener to extract images from the PDF
    static class ImageExtractionListener implements IEventListener {

        int pageNum;

        public ImageExtractionListener(int pageNum) {
            this.pageNum = pageNum;
        }

        @Override
        public void eventOccurred(IEventData iEventData, EventType eventType) {
            if (eventType == EventType.RENDER_IMAGE) {
                // Get the image render information
                ImageRenderInfo renderInfo = (ImageRenderInfo) iEventData;
                PdfImageXObject imgObj = renderInfo.getImage();

                try {
                    // Extract image bytes and save them as a PNG file
                    byte[] imgBytes = imgObj.getImageBytes();
                    String imageFilePath = "image_" + System.currentTimeMillis() + ".png";
                    try (FileOutputStream fos = new FileOutputStream(imageFilePath)) {
                        fos.write(imgBytes);
                        System.out.println("Page " + pageNum + " Image saved: " + imageFilePath);
                    }
                } catch (IOException e) {
                    // Handle any IO exceptions when saving the image
                    e.printStackTrace();
                }
            }
        }

        @Override
        public Set<EventType> getSupportedEvents() {
            // Define the events the listener will handle (only image rendering here)
            return Set.of(EventType.RENDER_IMAGE);
        }
    }

    public static void main(String[] args) {
        String inputPdfPath = "/Users/chuanzhizhu/Desktop/1780761576456134656.pdf";
        readTextAndImage(inputPdfPath);
    }
}
