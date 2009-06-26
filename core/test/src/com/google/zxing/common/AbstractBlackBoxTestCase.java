/*
 * Copyright 2008 ZXing authors
 *
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

package com.google.zxing.common;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;

import junit.framework.TestCase;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * @author Sean Owen
 * @author dswitkin@google.com (Daniel Switkin)
 */
public abstract class AbstractBlackBoxTestCase extends TestCase {

  protected static final Hashtable<DecodeHintType, Object> TRY_HARDER_HINT;
  static {
    TRY_HARDER_HINT = new Hashtable<DecodeHintType, Object>();
    TRY_HARDER_HINT.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
  }

  private static final FilenameFilter IMAGE_NAME_FILTER = new FilenameFilter() {
    public boolean accept(File dir, String name) {
      String lowerCase = name.toLowerCase();
      return lowerCase.endsWith(".jpg") || lowerCase.endsWith(".jpeg") ||
             lowerCase.endsWith(".gif") || lowerCase.endsWith(".png");
    }
  };

  public static class SummaryResults {
    private int totalFound;
    private int totalMustPass;
    private int totalTests;

    public SummaryResults() {
      totalFound = 0;
      totalMustPass = 0;
      totalTests = 0;
    }

    public SummaryResults(int found, int mustPass, int total) {
      totalFound = found;
      totalMustPass = mustPass;
      totalTests = total;
    }

    public void add(SummaryResults other) {
      totalFound += other.totalFound;
      totalMustPass += other.totalMustPass;
      totalTests += other.totalTests;
    }

    public String toString() {
      return "\nSUMMARY RESULTS:\n  Decoded " + totalFound + " images out of " + totalTests +
        " (" + (totalFound * 100 / totalTests) + "%, " + totalMustPass + " required)";
    }
  }

  private static class TestResult {
    private final int mustPassCount;
    private final int tryHarderCount;
    private final float rotation;

    TestResult(int mustPassCount, int tryHarderCount, float rotation) {
      this.mustPassCount = mustPassCount;
      this.tryHarderCount = tryHarderCount;
      this.rotation = rotation;
    }
    public int getMustPassCount() {
      return mustPassCount;
    }
    public int getTryHarderCount() {
      return tryHarderCount;
    }
    public float getRotation() {
      return rotation;
    }
  }

  private final File testBase;
  private final Reader barcodeReader;
  private final BarcodeFormat expectedFormat;
  private final List<TestResult> testResults;

  protected AbstractBlackBoxTestCase(String testBasePathSuffix,
                                     Reader barcodeReader,
                                     BarcodeFormat expectedFormat) {
    // A little workaround to prevent aggravation in my IDE
    File testBase = new File(testBasePathSuffix);
    if (!testBase.exists()) {
      // try starting with 'core' since the test base is often given as the project root
      testBase = new File("core/" + testBasePathSuffix);
    }
    this.testBase = testBase;
    this.barcodeReader = barcodeReader;
    this.expectedFormat = expectedFormat;
    testResults = new ArrayList<TestResult>();
  }

  /**
   * Adds a new test for the current directory of images.
   *
   * @param mustPassCount The number of images which must decode for the test to pass.
   * @param tryHarderCount The number of images which must pass using the try harder flag.
   * @param rotation The rotation in degrees clockwise to use for this test.
   */
  protected void addTest(int mustPassCount, int tryHarderCount, float rotation) {
    testResults.add(new TestResult(mustPassCount, tryHarderCount, rotation));
  }

  protected File[] getImageFiles() {
    assertTrue("Please run from the 'core' directory", testBase.exists());
    return testBase.listFiles(IMAGE_NAME_FILTER);
  }

  protected Reader getReader() {
    return barcodeReader;
  }

  protected Hashtable<DecodeHintType, Object> getHints() {
    return null;
  }

  // This workaround is used because AbstractNegativeBlackBoxTestCase overrides this method but does
  // not return SummaryResults.
  public void testBlackBox() throws IOException {
    testBlackBoxCountingResults();
  }

  public SummaryResults testBlackBoxCountingResults() throws IOException {
    assertFalse(testResults.isEmpty());

    File[] imageFiles = getImageFiles();
    int testCount = testResults.size();
    int[] passedCounts = new int[testCount];
    int[] tryHarderCounts = new int[testCount];
    for (File testImage : imageFiles) {
      System.out.println("Starting " + testImage.getAbsolutePath());

      BufferedImage image = ImageIO.read(testImage);

      String testImageFileName = testImage.getName();
      File expectedTextFile = new File(testBase,
          testImageFileName.substring(0, testImageFileName.indexOf('.')) + ".txt");
      String expectedText = readFileAsString(expectedTextFile);

      for (int x = 0; x < testCount; x++) {
        float rotation = testResults.get(x).getRotation();
        BufferedImage rotatedImage = rotateImage(image, rotation);
        LuminanceSource source = new BufferedImageLuminanceSource(rotatedImage);
        BinaryBitmap bitmap = new BinaryBitmap(new GlobalHistogramBinarizer(source));
        if (decode(bitmap, rotation, expectedText, false)) {
          passedCounts[x]++;
        }
        if (decode(bitmap, rotation, expectedText, true)) {
          tryHarderCounts[x]++;
        }
      }
    }

    // Print the results of all tests first
    int totalFound = 0;
    int totalMustPass = 0;
    for (int x = 0; x < testCount; x++) {
      System.out.println("Rotation " + testResults.get(x).getRotation() + " degrees:");
      System.out.println("  " + passedCounts[x] + " of " + imageFiles.length + " images passed ("
          + testResults.get(x).getMustPassCount() + " required)");
      System.out.println("  " + tryHarderCounts[x] + " of " + imageFiles.length +
          " images passed with try harder (" + testResults.get(x).getTryHarderCount() +
          " required)");
      totalFound += passedCounts[x];
      totalFound += tryHarderCounts[x];
      totalMustPass += testResults.get(x).getMustPassCount();
      totalMustPass += testResults.get(x).getTryHarderCount();
    }

    int totalTests = imageFiles.length * testCount * 2;
    System.out.println("TOTALS:\n  Decoded " + totalFound + " images out of " + totalTests +
      " (" + (totalFound * 100 / totalTests) + "%)");
    if (totalFound > totalMustPass) {
      System.out.println("  *** Test too lax by " + (totalFound - totalMustPass) + " images");
    }

    // Then run through again and assert if any failed
    for (int x = 0; x < testCount; x++) {
      assertTrue("Rotation " + testResults.get(x).getRotation() +
          " degrees: Too many images failed",
          passedCounts[x] >= testResults.get(x).getMustPassCount());
      assertTrue("Try harder, Rotation " + testResults.get(x).getRotation() +
          " degrees: Too many images failed",
          tryHarderCounts[x] >= testResults.get(x).getTryHarderCount());
    }
    return new SummaryResults(totalFound, totalMustPass, totalTests);
  }

  private boolean decode(BinaryBitmap source, float rotation, String expectedText,
                         boolean tryHarder) {
    Result result;
    String suffix = " (" + (tryHarder ? "try harder, " : "") + "rotation: " + rotation + ')';

    try {
      Hashtable<DecodeHintType, Object> hints = getHints();
      if (tryHarder) {
        if (hints == null) {
          hints = TRY_HARDER_HINT;
        } else {
          hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        }
      }
      result = barcodeReader.decode(source, hints);
    } catch (ReaderException re) {
      System.out.println(re + suffix);
      return false;
    }

    if (!expectedFormat.equals(result.getBarcodeFormat())) {
      System.out.println("Format mismatch: expected '" + expectedFormat + "' but got '" +
          result.getBarcodeFormat() + '\'' + suffix);
      return false;
    }

    String resultText = result.getText();
    if (!expectedText.equals(resultText)) {
      System.out.println("Mismatch: expected '" + expectedText + "' but got '" + resultText +
          '\'' +  suffix);
      return false;
    }
    return true;
  }

  private static String readFileAsString(File file) throws IOException {
    StringBuilder result = new StringBuilder((int) file.length());
    InputStreamReader reader = new InputStreamReader(new FileInputStream(file), Charset.forName("UTF8"));
    try {
      char[] buffer = new char[256];
      int charsRead;
      while ((charsRead = reader.read(buffer)) > 0) {
        result.append(buffer, 0, charsRead);
      }
    } finally {
      reader.close();
    }
    return result.toString();
  }

  protected static BufferedImage rotateImage(BufferedImage original, float degrees) {
    if (degrees == 0.0f) {
      return original;
    } else {
      AffineTransform at = new AffineTransform();
      at.rotate(Math.toRadians(degrees), original.getWidth() / 2.0, original.getHeight() / 2.0);
      BufferedImageOp op = new AffineTransformOp(at, AffineTransformOp.TYPE_BICUBIC);
      return op.filter(original, null);
    }
  }

}