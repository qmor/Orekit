/* Copyright 2002-2019 CS Systèmes d'Information
 * Licensed to CS Systèmes d'Information (CS) under one or more
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
package org.orekit.utils;

import java.util.List;

import org.hipparchus.linear.RealMatrix;
import org.hipparchus.linear.RealVector;
import org.orekit.propagation.SpacecraftState;

/**
 * Interface for Multiple shooting methods.
 * @author William Desprats
 */
public interface MultipleShooting {


    /** Return the list of corrected patch points.
     *  An optimizer is better suited for this problem
     * @return patchedSpacecraftStates patchedSpacecraftStates
     */
    List<SpacecraftState> compute();

    /** Compute the Jacobian matrix of the problem.
     *  @param propagatedSP List of propagated SpacecraftStates
     *  @return jacobianMatrix Jacobian matrix
     */
    RealMatrix computeJacobianMatrix(List<SpacecraftState> propagatedSP);

    /** Propagate a list of SpacecraftStates.
     *  @return propagatedSP propagated SpacecraftStates
     */
    List<SpacecraftState> propagatePatchedSpacecraftState();

    /** Compute the constraint of the problem.
     *  @param propagatedSP List of propagated SpacecraftStates
     *  @return fx constraint vector
     */
    double[] computeConstraint(List<SpacecraftState> propagatedSP);

    /** Update the trajectory.
     *  @param dx correction on the initial vector
     */
    void updateTrajectory(RealVector dx);
}
