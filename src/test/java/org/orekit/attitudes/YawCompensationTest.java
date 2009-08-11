/* Copyright 2002-2008 CS Communication & Systèmes
 * Licensed to CS Communication & Systèmes (CS) under one or more
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
package org.orekit.attitudes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.commons.math.geometry.Rotation;
import org.apache.commons.math.geometry.Vector3D;
import org.orekit.Utils;
import org.orekit.attitudes.NadirPointing;
import org.orekit.attitudes.YawCompensation;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.data.DataProvidersManager;
import org.orekit.errors.OrekitException;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.orbits.CircularOrbit;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.KeplerianPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.DateComponents;
import org.orekit.time.TimeComponents;
import org.orekit.time.UTCScale;
import org.orekit.utils.PVCoordinates;


public class YawCompensationTest {

    // Computation date 
    private AbsoluteDate date;
    
    // Reference frame = ITRF 2005C 
    private Frame frameITRF2005;
    
    // Satellite position
    CircularOrbit circOrbit;
    PVCoordinates pvSatITRF2005C;
    
    // Earth shape
    OneAxisEllipsoid earthShape;
    
    /** Test that pointed target and observed ground point remain the same 
     * with or without yaw compensation
     */
    @Test
    public void testTarget() throws OrekitException {

        //  Attitude laws
        // **************
        // Target pointing attitude law without yaw compensation
        NadirPointing nadirLaw = new NadirPointing(earthShape);
 
        // Target pointing attitude law with yaw compensation
        YawCompensation yawCompensLaw = new YawCompensation(nadirLaw);
       
        //  Check target
        // **************
        // without yaw compensation
        PVCoordinates noYawTarget = nadirLaw.getTargetInBodyFrame(date, pvSatITRF2005C, frameITRF2005);
         
        // with yaw compensation
        PVCoordinates yawTarget = yawCompensLaw.getTargetInBodyFrame(date, pvSatITRF2005C, frameITRF2005);

        // Check difference
        PVCoordinates targetDiff = new PVCoordinates(1.0, yawTarget, -1.0, noYawTarget);
        double normTargetDiffPos = targetDiff.getPosition().getNorm();
        double normTargetDiffVel = targetDiff.getVelocity().getNorm();
       
        assertTrue((normTargetDiffPos < Utils.epsilonTest)&&(normTargetDiffVel < Utils.epsilonTest));

        //  Check observed ground point
        // *****************************
        // without yaw compensation
        PVCoordinates noYawObserved = nadirLaw.getObservedGroundPoint(date, pvSatITRF2005C, frameITRF2005);

        // with yaw compensation
        PVCoordinates yawObserved = yawCompensLaw.getObservedGroundPoint(date, pvSatITRF2005C, frameITRF2005);

        // Check difference
        PVCoordinates observedDiff = new PVCoordinates(1.0, yawObserved, -1.0, noYawObserved);
        double normObservedDiffPos = observedDiff.getPosition().getNorm();
        double normObservedDiffVel = observedDiff.getVelocity().getNorm();
       
        assertTrue((normObservedDiffPos < Utils.epsilonTest)&&(normObservedDiffVel < Utils.epsilonTest));
   }

    /** Test that maximum yaw compensation is at ascending/descending node, 
     * and minimum yaw compensation is at maximum latitude.
     */
    @Test
    public void testCompensMinMax() throws OrekitException {

        //  Attitude laws
        // **************
        // Target pointing attitude law over satellite nadir at date, without yaw compensation
        NadirPointing nadirLaw = new NadirPointing(earthShape);
 
        // Target pointing attitude law with yaw compensation
        YawCompensation yawCompensLaw = new YawCompensation(nadirLaw);

        
        // Extrapolation over one orbital period (sec)
        double duration = circOrbit.getKeplerianPeriod();
        KeplerianPropagator extrapolator = new KeplerianPropagator(circOrbit);
        
        // Extrapolation initializations
        double delta_t = 15.0; // extrapolation duration in seconds
        AbsoluteDate extrapDate = new AbsoluteDate(date, 0.0); // extrapolation start date

        // Min initialization
        double yawMin = 1.e+12;
        double latMin = 0.;
        
        while (extrapDate.durationFrom(date) < duration)  {
            extrapDate = new AbsoluteDate(extrapDate, delta_t);

            // Extrapolated orbit state at date
            SpacecraftState extrapOrbit = extrapolator.propagate(extrapDate);
            PVCoordinates extrapPvSatEME2000 = extrapOrbit.getPVCoordinates();
            
            // Satellite latitude at date
            double extrapLat = earthShape.transform(extrapPvSatEME2000.getPosition(), FramesFactory.getEME2000(), extrapDate).getLatitude();
            
            // Compute yaw compensation angle -- rotations composition
            double yawAngle = yawCompensLaw.getYawAngle(extrapDate, extrapPvSatEME2000, FramesFactory.getEME2000());
                        
            // Update minimum yaw compensation angle
            if (Math.abs(yawAngle) <= yawMin) {
                yawMin = Math.abs(yawAngle);
                latMin = extrapLat;
            }           

            //     Checks
            // ------------------
            
            // 1/ Check yaw values around ascending node (max yaw)
            if ((Math.abs(extrapLat) < Math.toRadians(2.)) &&
                (extrapPvSatEME2000.getVelocity().getZ() >= 0. )) {
                assertTrue((Math.abs(yawAngle) >= Math.toRadians(2.8488)) 
                        && (Math.abs(yawAngle) <= Math.toRadians(2.8532)));
            }
            
            // 2/ Check yaw values around maximum positive latitude (min yaw)
            if ( extrapLat > Math.toRadians(50.) ) {
                assertTrue((Math.abs(yawAngle) <= Math.toRadians(0.2628)) 
                        && (Math.abs(yawAngle) >= Math.toRadians(0.0032)));
            }
            
            // 3/ Check yaw values around descending node (max yaw)
            if ( (Math.abs(extrapLat) < Math.toRadians(2.))
                    && (extrapPvSatEME2000.getVelocity().getZ() <= 0. ) )
            {
                assertTrue((Math.abs(yawAngle) >= Math.toRadians(2.8485)) 
                             && (Math.abs(yawAngle) <= Math.toRadians(2.8536)));
            }
         
            // 4/ Check yaw values around maximum negative latitude (min yaw)
            if ( extrapLat < Math.toRadians(-50.) )
            {
                assertTrue((Math.abs(yawAngle) <= Math.toRadians(0.2359)) 
                             && (Math.abs(yawAngle) >= Math.toRadians(0.0141)));
            }

        }
        
        // 5/ Check that minimum yaw compensation value is around maximum latitude
        assertEquals(Math.toRadians( 0.003229), yawMin, Utils.epsilonAngle);
        assertEquals(Math.toRadians(50.214853), latMin, Utils.epsilonAngle);

    }

    /** Test that compensation rotation axis is Zsat, yaw axis
     */
    @Test
    public void testCompensAxis() throws OrekitException {

        PVCoordinates pvSatEME2000 = circOrbit.getPVCoordinates();

        //  Attitude laws
        // **************
        // Target pointing attitude law over satellite nadir at date, without yaw compensation
        NadirPointing nadirLaw = new NadirPointing(earthShape);
 
        // Target pointing attitude law with yaw compensation
        YawCompensation yawCompensLaw = new YawCompensation(nadirLaw);

        // Get attitude rotations from non yaw compensated / yaw compensated laws
        Rotation rotNoYaw = nadirLaw.getState(date, pvSatEME2000, FramesFactory.getEME2000()).getRotation();
        Rotation rotYaw = yawCompensLaw.getState(date, pvSatEME2000, FramesFactory.getEME2000()).getRotation();
            
        // Compose rotations composition
        Rotation compoRot = rotYaw.applyTo(rotNoYaw.revert());
        Vector3D yawAxis = compoRot.getAxis();

        // Check axis
        assertEquals(0., yawAxis.getX(), Utils.epsilonTest);
        assertEquals(0., yawAxis.getY(), Utils.epsilonTest);
        assertEquals(1., yawAxis.getZ(), Utils.epsilonTest);

    }

    @Before
    public void setUp() {
        try {
            String root = getClass().getClassLoader().getResource("regular-data").getPath();
            System.setProperty(DataProvidersManager.OREKIT_DATA_PATH, root);

            // Computation date
            date = new AbsoluteDate(new DateComponents(2008, 04, 07),
                                    TimeComponents.H00,
                                    UTCScale.getInstance());

            // Body mu
            final double mu = 3.9860047e14;
            
            // Reference frame = ITRF 2005
            frameITRF2005 = FramesFactory.getITRF2005(true);

            //  Satellite position
            circOrbit =
                new CircularOrbit(7178000.0, 0.5e-4, -0.5e-4, Math.toRadians(50.), Math.toRadians(270.),
                                       Math.toRadians(5.300), CircularOrbit.MEAN_LONGITUDE_ARGUMENT, 
                                       FramesFactory.getEME2000(), date, mu);
            
            pvSatITRF2005C = circOrbit.getPVCoordinates(frameITRF2005);
            
            // Elliptic earth shape */
            earthShape =
                new OneAxisEllipsoid(6378136.460, 1 / 298.257222101, frameITRF2005);
            
        } catch (OrekitException oe) {
            fail(oe.getMessage());
        }

    }

    @After
    public void tearDown() {
        date = null;
        frameITRF2005 = null;
        circOrbit = null;
        pvSatITRF2005C = null;
        earthShape = null;
    }

}

