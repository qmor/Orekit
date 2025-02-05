/* Copyright 2002-2022 CS GROUP
 * Licensed to CS GROUP (CS) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * CS licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.orekit.forces.gravity.potential;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.hipparchus.util.FastMath;
import org.hipparchus.util.Precision;
import org.orekit.annotation.DefaultDataContext;
import org.orekit.data.DataContext;
import org.orekit.errors.OrekitException;
import org.orekit.errors.OrekitMessages;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.DateComponents;
import org.orekit.time.TimeScale;
import org.orekit.utils.Constants;

/** Reader for the SHM gravity field format.
 *
 * <p> This format was used to describe the gravity field of EIGEN models
 * published by the GFZ Potsdam up to 2003. It was then replaced by
 * {@link ICGEMFormatReader ICGEM format}. The SHM format is described in
 * <a href="http://op.gfz-potsdam.de/champ/docs_CHAMP/CH-FORMAT-REFLINKS.html"> Potsdam university
 * website</a>.
 *
 * <p> The proper way to use this class is to call the {@link GravityFieldFactory}
 *  which will determine which reader to use with the selected gravity field file.</p>
 *
 * @see GravityFields
 * @author Fabien Maussion
 */
public class SHMFormatReader extends PotentialCoefficientsReader {

    /** Pattern for delimiting regular expressions. */
    private static final Pattern SEPARATOR = Pattern.compile("\\s+");

    /** First field labels. */
    private static final String GRCOEF = "GRCOEF";

    /** Second field labels. */
    private static final String GRCOF2 = "GRCOF2";

    /** Drift coefficients labels. */
    private static final String GRDOTA = "GRDOTA";

    /** Reference date. */
    private AbsoluteDate referenceDate;

    /** Secular drift of the cosine coefficients. */
    private final List<List<Double>> cDot;

    /** Secular drift of the sine coefficients. */
    private final List<List<Double>> sDot;

    /** Simple constructor.
     *
     * <p>This constructor uses the {@link DataContext#getDefault() default data context}.
     *
     * @param supportedNames regular expression for supported files names
     * @param missingCoefficientsAllowed if true, allows missing coefficients in the input data
     * @see #SHMFormatReader(String, boolean, TimeScale)
     */
    @DefaultDataContext
    public SHMFormatReader(final String supportedNames, final boolean missingCoefficientsAllowed) {
        this(supportedNames, missingCoefficientsAllowed,
                DataContext.getDefault().getTimeScales().getTT());
    }

    /** Simple constructor.
     * @param supportedNames regular expression for supported files names
     * @param missingCoefficientsAllowed if true, allows missing coefficients in the input data
     * @param timeScale for parsing dates.
     * @since 10.1
     */
    public SHMFormatReader(final String supportedNames,
                           final boolean missingCoefficientsAllowed,
                           final TimeScale timeScale) {
        super(supportedNames, missingCoefficientsAllowed, timeScale);
        referenceDate = null;
        cDot = new ArrayList<>();
        sDot = new ArrayList<>();
    }

    /** {@inheritDoc} */
    public void loadData(final InputStream input, final String name)
        throws IOException, ParseException, OrekitException {

        // reset the indicator before loading any data
        setReadComplete(false);
        referenceDate = null;
        cDot.clear();
        sDot.clear();

        boolean    normalized = false;
        TideSystem tideSystem = TideSystem.UNKNOWN;

        boolean okEarth  = false;
        boolean okSHM    = false;
        boolean okCoeffs = false;
        double[][] c     = null;
        double[][] s     = null;
        String line      = null;
        int lineNumber   = 1;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            line = r.readLine();
            if (line != null &&
                "FIRST ".equals(line.substring(0, 6)) &&
                "SHM    ".equals(line.substring(49, 56))) {
                for (line = r.readLine(); line != null; line = r.readLine()) {
                    lineNumber++;
                    if (line.length() >= 6) {
                        final String[] tab = SEPARATOR.split(line);

                        // read the earth values
                        if ("EARTH".equals(tab[0])) {
                            setMu(parseDouble(tab[1]));
                            setAe(parseDouble(tab[2]));
                            okEarth = true;
                        }

                        // initialize the arrays
                        if ("SHM".equals(tab[0])) {

                            final int degree = FastMath.min(getMaxParseDegree(), Integer.parseInt(tab[1]));
                            final int order  = FastMath.min(getMaxParseOrder(), degree);
                            c = buildTriangularArray(degree, order, missingCoefficientsAllowed() ? 0.0 : Double.NaN);
                            s = buildTriangularArray(degree, order, missingCoefficientsAllowed() ? 0.0 : Double.NaN);
                            final String lowerCaseLine = line.toLowerCase(Locale.US);
                            normalized = lowerCaseLine.contains("fully normalized");
                            if (lowerCaseLine.contains("exclusive permanent tide")) {
                                tideSystem = TideSystem.TIDE_FREE;
                            } else {
                                tideSystem = TideSystem.UNKNOWN;
                            }
                            okSHM = true;
                        }

                        // fill the arrays
                        if (GRCOEF.equals(line.substring(0, 6)) || GRCOF2.equals(tab[0]) || GRDOTA.equals(tab[0])) {
                            final int i = Integer.parseInt(tab[1]);
                            final int j = Integer.parseInt(tab[2]);
                            if (i < c.length && j < c[i].length) {
                                if (GRDOTA.equals(tab[0])) {

                                    // store the secular drift coefficients
                                    extendListOfLists(cDot, i, j, 0.0);
                                    extendListOfLists(sDot, i, j, 0.0);
                                    parseCoefficient(tab[3], cDot, i, j, "Cdot", name);
                                    parseCoefficient(tab[4], sDot, i, j, "Sdot", name);

                                    // check the reference date (format yyyymmdd)
                                    final DateComponents localRef = new DateComponents(Integer.parseInt(tab[7].substring(0, 4)),
                                                                                       Integer.parseInt(tab[7].substring(4, 6)),
                                                                                       Integer.parseInt(tab[7].substring(6, 8)));
                                    if (referenceDate == null) {
                                        // first reference found, store it
                                        referenceDate = toDate(localRef);
                                    } else if (!referenceDate.equals(toDate(localRef))) {
                                        final AbsoluteDate localDate = toDate(localRef);
                                        throw new OrekitException(OrekitMessages.SEVERAL_REFERENCE_DATES_IN_GRAVITY_FIELD,
                                                                  referenceDate, localDate, name,
                                                                  localDate.durationFrom(referenceDate));
                                    }

                                } else {

                                    // store the constant coefficients
                                    parseCoefficient(tab[3], c, i, j, "C", name);
                                    parseCoefficient(tab[4], s, i, j, "S", name);
                                    okCoeffs = true;

                                }
                            }
                        }

                    }
                }
            }
        } catch (NumberFormatException nfe) {
            throw new OrekitException(OrekitMessages.UNABLE_TO_PARSE_LINE_IN_FILE,
                                      lineNumber, name, line);
        }

        if (missingCoefficientsAllowed() && c.length > 0 && c[0].length > 0) {
            // ensure at least the (0, 0) element is properly set
            if (Precision.equals(c[0][0], 0.0, 0)) {
                c[0][0] = 1.0;
            }
        }

        if (!(okEarth && okSHM && okCoeffs)) {
            String loaderName = getClass().getName();
            loaderName = loaderName.substring(loaderName.lastIndexOf('.') + 1);
            throw new OrekitException(OrekitMessages.UNEXPECTED_FILE_FORMAT_ERROR_FOR_LOADER,
                                      name, loaderName);
        }

        setRawCoefficients(normalized, c, s, name);
        setTideSystem(tideSystem);
        setReadComplete(true);

    }

    /** Get a provider for read spherical harmonics coefficients.
     * <p>
     * SHM fields do include time-dependent parts which are taken into account
     * in the returned provider.
     * </p>
     * @param wantNormalized if true, the provider will provide normalized coefficients,
     * otherwise it will provide un-normalized coefficients
     * @param degree maximal degree
     * @param order maximal order
     * @return a new provider
     * @since 6.0
     */
    public RawSphericalHarmonicsProvider getProvider(final boolean wantNormalized,
                                                     final int degree, final int order) {

        // get the constant part
        RawSphericalHarmonicsProvider provider = getConstantProvider(wantNormalized, degree, order);

        if (!cDot.isEmpty()) {

            // add the secular trend layer
            final double[][] cArray = toArray(cDot);
            final double[][] sArray = toArray(sDot);
            rescale(1.0 / Constants.JULIAN_YEAR, true, cArray, sArray, wantNormalized, cArray, sArray);
            provider = new SecularTrendSphericalHarmonics(provider, referenceDate, cArray, sArray);

        }

        return provider;

    }

}
