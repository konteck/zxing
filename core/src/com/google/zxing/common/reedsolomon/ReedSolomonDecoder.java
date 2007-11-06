/*
 * Copyright 2007 Google Inc.
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

package com.google.zxing.common.reedsolomon;

import java.util.Vector;

/**
 * <p>Implements Reed-Solomon decoding, as the name implies.</p>
 *
 * <p>The algorithm will not be explained here, but the following references were helpful
 * in creating this implementation:</p>
 *
 * <ul>
 * <li>Bruce Maggs.
 *  <a href="http://www.cs.cmu.edu/afs/cs.cmu.edu/project/pscico-guyb/realworld/www/rs_decode.ps">
 *  "Decoding Reed-Solomon Codes"</a> (see discussion of Forney's Formula)</li>
 * <li>J.I. Hall. <a href="www.mth.msu.edu/~jhall/classes/codenotes/GRS.pdf">
 * "Chapter 5. Generalized Reed-Solomon Codes"</a>
 * (see discussion of Euclidean algorithm)</li>
 * </ul>
 *
 * <p>Much credit is due to William Rucklidge since portions of this code are an indirect
 * port of his C++ Reed-Solomon implementation.</p>
 *
 * @author srowen@google.com (Sean Owen)
 * @author William Rucklidge
 */
public final class ReedSolomonDecoder {
  
  private ReedSolomonDecoder() {
  }

  /**
   * <p>Decodes given set of received codewords, which include both data and error-correction
   * codewords. Really, this means it uses Reed-Solomon to detect and correct errors, in-place,
   * in the input.</p>
   *
   * @param received data and error-correction codewords
   * @param twoS number of error-correction codewords available
   * @throws ReedSolomonException if decoding fails for any reaosn
   */
  public static void decode(int[] received, int twoS) throws ReedSolomonException {
    GF256Poly poly = new GF256Poly(received);
    int[] syndromeCoefficients = new int[twoS];
    for (int i = 0; i < twoS; i++) {
      syndromeCoefficients[syndromeCoefficients.length - 1 - i] = poly.evaluateAt(GF256.exp(i));
    }
    GF256Poly syndrome = new GF256Poly(syndromeCoefficients);
    if (!syndrome.isZero()) { // Error
      GF256Poly[] sigmaOmega =
          runEuclideanAlgorithm(GF256Poly.buildMonomial(twoS, 1), syndrome, twoS);
      int[] errorLocations = findErrorLocations(sigmaOmega[0]);
      int[] errorMagnitudes = findErrorMagnitudes(sigmaOmega[1], errorLocations);
      for (int i = 0; i < errorLocations.length; i++) {
        int position = received.length - 1 - GF256.log(errorLocations[i]);
        received[position] = GF256.addOrSubtract(received[position], errorMagnitudes[i]);
      }
    }
  }

  private static GF256Poly[] runEuclideanAlgorithm(GF256Poly a, GF256Poly b, int R)
      throws ReedSolomonException {
    // Assume a's degree is >= b's
    if (a.getDegree() < b.getDegree()) {
      GF256Poly temp = a;
      a = b;
      b = temp;
    }

    GF256Poly rLast = a;
    GF256Poly r = b;
    GF256Poly sLast = GF256Poly.ONE;
    GF256Poly s = GF256Poly.ZERO;
    GF256Poly tLast = GF256Poly.ZERO;
    GF256Poly t = GF256Poly.ONE;

    // Run Euclidean algorithm until r's degree is less than R/2
    while (r.getDegree() >= R / 2) {
      GF256Poly rLastLast = rLast;
      GF256Poly sLastLast = sLast;
      GF256Poly tLastLast = tLast;
      rLast = r;
      sLast = s;
      tLast = t;

      // Divide rLastLast by rLast, with quotient in q and remainder in r
      if (rLast.isZero()) {
        // Oops, Euclidean algorithm already terminated?
        throw new ReedSolomonException("r_{i-1} was zero");
      }
      r = rLastLast;
      GF256Poly q = GF256Poly.ZERO;
      int denominatorLeadingTerm = rLast.getCoefficient(rLast.getDegree());
      int dltInverse = GF256.inverse(denominatorLeadingTerm);
      while (r.getDegree() >= rLast.getDegree() && !r.isZero()) {
        int degreeDiff = r.getDegree() - rLast.getDegree();
        int scale = GF256.multiply(r.getCoefficient(r.getDegree()), dltInverse);
        q = q.addOrSubtract(GF256Poly.buildMonomial(degreeDiff, scale));
        r = r.addOrSubtract(rLast.multiplyByMonomial(degreeDiff, scale));
      }

      s = q.multiply(sLast).addOrSubtract(sLastLast);
      t = q.multiply(tLast).addOrSubtract(tLastLast);
    }

    int sigmaTildeAtZero = t.getCoefficient(0);
    if (sigmaTildeAtZero == 0) {
      throw new ReedSolomonException("sigmaTilde(0) was zero");
    }

    int inverse = GF256.inverse(sigmaTildeAtZero);
    GF256Poly sigma = t.multiply(inverse);
    GF256Poly omega = r.multiply(inverse);
    return new GF256Poly[] { sigma, omega };
  }

  private static int[] findErrorLocations(GF256Poly errorLocator)
      throws ReedSolomonException {
    // This is a direct application of Chien's search
    Vector errorLocations = new Vector(3);
    for (int i = 1; i < 256; i++) {
      if (errorLocator.evaluateAt(i) == 0) {
        errorLocations.addElement(new Integer(GF256.inverse(i)));
      }
    }
    if (errorLocations.size() != errorLocator.getDegree()) {
      throw new ReedSolomonException("Error locator degree does not match number of roots");
    }
    int[] result = new int[errorLocations.size()]; // Can't use toArray() here
    for (int i = 0; i < result.length; i++) {
      result[i] = ((Integer) errorLocations.elementAt(i)).intValue();
    }
    return result;
  }

  private static int[] findErrorMagnitudes(GF256Poly errorEvaluator,
                                           int[] errorLocations) {
    // This is directly applying Forney's Formula
    int s = errorLocations.length;
    int[] result = new int[s];
    for (int i = 0; i < errorLocations.length; i++) {
      int xiInverse = GF256.inverse(errorLocations[i]);
      int denominator = 1;
      for (int j = 0; j < s; j++) {
        if (i != j) {
          denominator = GF256.multiply(denominator,
              GF256.addOrSubtract(1, GF256.multiply(errorLocations[j], xiInverse)));
        }
      }
      result[i] = GF256.multiply(errorEvaluator.evaluateAt(xiInverse),
                                 GF256.inverse(denominator));
    }
    return result;
  }

}