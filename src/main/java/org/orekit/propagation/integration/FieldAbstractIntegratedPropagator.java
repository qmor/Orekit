/* Copyright 2002-2021 CS GROUP
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
package org.orekit.propagation.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.hipparchus.CalculusFieldElement;
import org.hipparchus.Field;
import org.hipparchus.exception.MathIllegalArgumentException;
import org.hipparchus.exception.MathIllegalStateException;
import org.hipparchus.ode.FieldDenseOutputModel;
import org.hipparchus.ode.FieldExpandableODE;
import org.hipparchus.ode.FieldODEIntegrator;
import org.hipparchus.ode.FieldODEState;
import org.hipparchus.ode.FieldODEStateAndDerivative;
import org.hipparchus.ode.FieldOrdinaryDifferentialEquation;
import org.hipparchus.ode.FieldSecondaryODE;
import org.hipparchus.ode.events.Action;
import org.hipparchus.ode.events.FieldEventHandlerConfiguration;
import org.hipparchus.ode.events.FieldODEEventHandler;
import org.hipparchus.ode.sampling.AbstractFieldODEStateInterpolator;
import org.hipparchus.ode.sampling.FieldODEStateInterpolator;
import org.hipparchus.ode.sampling.FieldODEStepHandler;
import org.hipparchus.util.MathArrays;
import org.hipparchus.util.Precision;
import org.orekit.attitudes.AttitudeProvider;
import org.orekit.errors.OrekitException;
import org.orekit.errors.OrekitInternalError;
import org.orekit.errors.OrekitMessages;
import org.orekit.frames.Frame;
import org.orekit.orbits.OrbitType;
import org.orekit.orbits.PositionAngle;
import org.orekit.propagation.FieldAbstractPropagator;
import org.orekit.propagation.FieldBoundedPropagator;
import org.orekit.propagation.FieldEphemerisGenerator;
import org.orekit.propagation.FieldSpacecraftState;
import org.orekit.propagation.FieldStackableGenerator;
import org.orekit.propagation.PropagationType;
import org.orekit.propagation.events.FieldEventDetector;
import org.orekit.propagation.sampling.FieldOrekitStepHandler;
import org.orekit.propagation.sampling.FieldOrekitStepInterpolator;
import org.orekit.time.FieldAbsoluteDate;
import org.orekit.utils.FieldArrayDictionary;


/** Common handling of {@link org.orekit.propagation.FieldPropagator FieldPropagator}
 *  methods for both numerical and semi-analytical propagators.
 *  @param <T> the type of the field elements
 *  @author Luc Maisonobe
 */
public abstract class FieldAbstractIntegratedPropagator<T extends CalculusFieldElement<T>> extends FieldAbstractPropagator<T> {

    /** Event detectors not related to force models. */
    private final List<FieldEventDetector<T>> detectors;

    /** Step handlers dedicated to ephemeris generation. */
    private final List<FieldStoringStepHandler> ephemerisGenerators;

    /** Integrator selected by the user for the orbital extrapolation process. */
    private final FieldODEIntegrator<T> integrator;

    /** Integrable generators.
     * @since 11.1
     */
    private final List<FieldIntegrableGenerator<T>> integrableGenerators;

    /** Counter for differential equations calls. */
    private int calls;

    /** Mapper between raw double components and space flight dynamics objects. */
    private FieldStateMapper<T> stateMapper;

    /** Flag for resetting the state at end of propagation. */
    private boolean resetAtEnd;

    /** Type of orbit to output (mean or osculating) <br/>
     * <p>
     * This is used only in the case of semianalitical propagators where there is a clear separation between
     * mean and short periodic elements. It is ignored by the Numerical propagator.
     * </p>
     */
    private PropagationType propagationType;

    /** Build a new instance.
     * @param integrator numerical integrator to use for propagation.
     * @param propagationType type of orbit to output (mean or osculating).
     * @param field Field used by default
     */
    protected FieldAbstractIntegratedPropagator(final Field<T> field, final FieldODEIntegrator<T> integrator, final PropagationType propagationType) {
        super(field);
        detectors            = new ArrayList<>();
        ephemerisGenerators  = new ArrayList<>();
        integrableGenerators = new ArrayList<>();
        this.integrator      = integrator;
        this.propagationType = propagationType;
        this.resetAtEnd      = true;
    }

    /** Allow/disallow resetting the initial state at end of propagation.
     * <p>
     * By default, at the end of the propagation, the propagator resets the initial state
     * to the final state, thus allowing a new propagation to be started from there without
     * recomputing the part already performed. Calling this method with {@code resetAtEnd} set
     * to false changes prevents such reset.
     * </p>
     * @param resetAtEnd if true, at end of each propagation, the {@link
     * #getInitialState() initial state} will be reset to the final state of
     * the propagation, otherwise the initial state will be preserved
     * @since 9.0
     */
    public void setResetAtEnd(final boolean resetAtEnd) {
        this.resetAtEnd = resetAtEnd;
    }

    /** Initialize the mapper.
     * @param field Field used by default
     */
    protected void initMapper(final Field<T> field) {
        final T zero = field.getZero();
        stateMapper = createMapper(null, zero.add(Double.NaN), null, null, null, null);
    }

    /**  {@inheritDoc} */
    public void setAttitudeProvider(final AttitudeProvider attitudeProvider) {
        super.setAttitudeProvider(attitudeProvider);
        stateMapper = createMapper(stateMapper.getReferenceDate(), stateMapper.getMu(),
                                   stateMapper.getOrbitType(), stateMapper.getPositionAngleType(),
                                   attitudeProvider, stateMapper.getFrame());
    }

    /** Set propagation orbit type.
     * @param orbitType orbit type to use for propagation
     */
    protected void setOrbitType(final OrbitType orbitType) {
        stateMapper = createMapper(stateMapper.getReferenceDate(), stateMapper.getMu(),
                                   orbitType, stateMapper.getPositionAngleType(),
                                   stateMapper.getAttitudeProvider(), stateMapper.getFrame());
    }

    /** Get propagation parameter type.
     * @return orbit type used for propagation
     */
    protected OrbitType getOrbitType() {
        return stateMapper.getOrbitType();
    }

    /** Check if only the mean elements should be used in a semianalitical propagation.
     * @return {@link PropagationType MEAN} if only mean elements have to be used or
     *         {@link PropagationType OSCULATING} if osculating elements have to be also used.
     */
    protected PropagationType isMeanOrbit() {
        return propagationType;
    }

    /** Set position angle type.
     * <p>
     * The position parameter type is meaningful only if {@link
     * #getOrbitType() propagation orbit type}
     * support it. As an example, it is not meaningful for propagation
     * in {@link OrbitType#CARTESIAN Cartesian} parameters.
     * </p>
     * @param positionAngleType angle type to use for propagation
     */
    protected void setPositionAngleType(final PositionAngle positionAngleType) {
        stateMapper = createMapper(stateMapper.getReferenceDate(), stateMapper.getMu(),
                                   stateMapper.getOrbitType(), positionAngleType,
                                   stateMapper.getAttitudeProvider(), stateMapper.getFrame());
    }

    /** Get propagation parameter type.
     * @return angle type to use for propagation
     */
    protected PositionAngle getPositionAngleType() {
        return stateMapper.getPositionAngleType();
    }

    /** Set the central attraction coefficient μ.
     * @param mu central attraction coefficient (m³/s²)
     */
    public void setMu(final T mu) {
        stateMapper = createMapper(stateMapper.getReferenceDate(), mu,
                                   stateMapper.getOrbitType(), stateMapper.getPositionAngleType(),
                                   stateMapper.getAttitudeProvider(), stateMapper.getFrame());
    }

    /** Get the central attraction coefficient μ.
     * @return mu central attraction coefficient (m³/s²)
     * @see #setMu(CalculusFieldElement)
     */
    public T getMu() {
        return stateMapper.getMu();
    }

    /** Get the number of calls to the differential equations computation method.
     * <p>The number of calls is reset each time the {@link #propagate(FieldAbsoluteDate)}
     * method is called.</p>
     * @return number of calls to the differential equations computation method
     */
    public int getCalls() {
        return calls;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isAdditionalStateManaged(final String name) {

        // first look at already integrated states
        if (super.isAdditionalStateManaged(name)) {
            return true;
        }

        // then look at states we integrate ourselves
        for (final FieldIntegrableGenerator<T> generator : integrableGenerators) {
            if (generator.getName().equals(name)) {
                return true;
            }
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override
    public String[] getManagedAdditionalStates() {
        final String[] alreadyIntegrated = super.getManagedAdditionalStates();
        final String[] managed = new String[alreadyIntegrated.length + integrableGenerators.size()];
        System.arraycopy(alreadyIntegrated, 0, managed, 0, alreadyIntegrated.length);
        for (int i = 0; i < integrableGenerators.size(); ++i) {
            managed[i + alreadyIntegrated.length] = integrableGenerators.get(i).getName();
        }
        return managed;
    }

    /** Add a set of user-specified equations to be integrated along with the orbit propagation.
     * @param additional additional equations
     * @deprecated as of 11.1, replaced by {@link #addIntegrableGenerator(IntegrableStateUpdater)}
     */
    @Deprecated
    public void addAdditionalEquations(final FieldAdditionalEquations<T> additional) {
        addIntegrableGenerator(new FieldAdditionalEquationAdapter<>(additional));
    }

    /** Add an generator for user-specified state parameters to be integrated along with the orbit propagation.
     * @param generator generator for additional state
     * @see #addClosedFormGenerator(FieldStackableGenerator)
     * @since 11.1
     */
    public void addIntegrableGenerator(final FieldIntegrableGenerator<T> generator) {

        // check if the name is already used
        if (isAdditionalStateManaged(generator.getName())) {
            // this set of equations is already registered, complain
            throw new OrekitException(OrekitMessages.ADDITIONAL_STATE_NAME_ALREADY_IN_USE,
                                      generator.getName());
        }

        // this is really a new set of equations, add it
        integrableGenerators.add(generator);

    }

    /** Get an unmodifiable list of generators for additional state.
     * @return generators for the additional states
     * @since 11.1
     */
    public List<FieldIntegrableGenerator<T>> getIntegrableGenerators() {
        return Collections.unmodifiableList(integrableGenerators);
    }

    /** {@inheritDoc} */
    @Override
    protected Collection<FieldStackableGenerator<T>> getAllGenerators() {
        final List<FieldStackableGenerator<T>> allUpdaters = new ArrayList<>(super.getAllGenerators());
        allUpdaters.addAll(integrableGenerators);
        return allUpdaters;
    }

    /** {@inheritDoc} */
    public <D extends FieldEventDetector<T>> void addEventDetector(final D detector) {
        detectors.add(detector);
    }

    /** {@inheritDoc} */
    public Collection<FieldEventDetector<T>> getEventsDetectors() {
        return Collections.unmodifiableCollection(detectors);
    }

    /** {@inheritDoc} */
    public void clearEventsDetectors() {
        detectors.clear();
    }

    /** Set up all user defined event detectors.
     */
    protected void setUpUserEventDetectors() {
        for (final FieldEventDetector<T> detector : detectors) {
            setUpEventDetector(integrator, detector);
        }
    }

    /** Wrap an Orekit event detector and register it to the integrator.
     * @param integ integrator into which event detector should be registered
     * @param detector event detector to wrap
     */
    protected void setUpEventDetector(final FieldODEIntegrator<T> integ, final FieldEventDetector<T> detector) {
        integ.addEventHandler(new FieldAdaptedEventDetector(detector),
                              detector.getMaxCheckInterval().getReal(),
                              detector.getThreshold().getReal(),
                              detector.getMaxIterationCount());
    }

    /** {@inheritDoc} */
    @Override
    public FieldEphemerisGenerator<T> getEphemerisGenerator() {
        final FieldStoringStepHandler storingHandler = new FieldStoringStepHandler();
        ephemerisGenerators.add(storingHandler);
        return storingHandler;
    }

    /** Create a mapper between raw double components and spacecraft state.
    /** Simple constructor.
     * <p>
     * The position parameter type is meaningful only if {@link
     * #getOrbitType() propagation orbit type}
     * support it. As an example, it is not meaningful for propagation
     * in {@link OrbitType#CARTESIAN Cartesian} parameters.
     * </p>
     * @param referenceDate reference date
     * @param mu central attraction coefficient (m³/s²)
     * @param orbitType orbit type to use for mapping
     * @param positionAngleType angle type to use for propagation
     * @param attitudeProvider attitude provider
     * @param frame inertial frame
     * @return new mapper
     */
    protected abstract FieldStateMapper<T> createMapper(FieldAbsoluteDate<T> referenceDate, T mu,
                                                        OrbitType orbitType, PositionAngle positionAngleType,
                                                        AttitudeProvider attitudeProvider, Frame frame);

    /** Get the differential equations to integrate (for main state only).
     * @param integ numerical integrator to use for propagation.
     * @return differential equations for main state
     */
    protected abstract MainStateEquations<T> getMainStateEquations(FieldODEIntegrator<T> integ);

    /** {@inheritDoc} */
    public FieldSpacecraftState<T> propagate(final FieldAbsoluteDate<T> target) {
        if (getStartDate() == null) {
            if (getInitialState() == null) {
                throw new OrekitException(OrekitMessages.INITIAL_STATE_NOT_SPECIFIED_FOR_ORBIT_PROPAGATION);
            }
            setStartDate(getInitialState().getDate());
        }
        return propagate(getStartDate(), target);
    }

    /** {@inheritDoc} */
    public FieldSpacecraftState<T> propagate(final FieldAbsoluteDate<T> tStart, final FieldAbsoluteDate<T> tEnd) {

        if (getInitialState() == null) {
            throw new OrekitException(OrekitMessages.INITIAL_STATE_NOT_SPECIFIED_FOR_ORBIT_PROPAGATION);
        }

        // make sure the integrator will be reset properly even if we change its events handlers and step handlers
        try (IntegratorResetter<T> resetter = new IntegratorResetter<>(integrator)) {

            if (!tStart.equals(getInitialState().getDate())) {
                // if propagation start date is not initial date,
                // propagate from initial to start date without event detection
                integrateDynamics(tStart);
            }

            // set up events added by user
            setUpUserEventDetectors();

            // set up step handlers
            for (final FieldOrekitStepHandler<T> handler : getMultiplexer().getHandlers()) {
                integrator.addStepHandler(new FieldAdaptedStepHandler(handler));
            }
            for (final FieldStoringStepHandler generator : ephemerisGenerators) {
                generator.setEndDate(tEnd);
                integrator.addStepHandler(generator);
            }

            // propagate from start date to end date with event detection
            return integrateDynamics(tEnd);

        }

    }

    /** Propagation with or without event detection.
     * @param tEnd target date to which orbit should be propagated
     * @return state at end of propagation
     */
    private FieldSpacecraftState<T> integrateDynamics(final FieldAbsoluteDate<T> tEnd) {
        try {

            initializePropagation();

            if (getInitialState().getDate().equals(tEnd)) {
                // don't extrapolate
                return getInitialState();
            }
            // space dynamics view
            stateMapper = createMapper(getInitialState().getDate(), stateMapper.getMu(),
                                       stateMapper.getOrbitType(), stateMapper.getPositionAngleType(),
                                       stateMapper.getAttitudeProvider(), getInitialState().getFrame());


            // set propagation orbit type
            //final FieldOrbit<T> initialOrbit = stateMapper.getOrbitType().convertType(getInitialState().getOrbit());
            if (Double.isNaN(getMu().getReal())) {
                setMu(getInitialState().getMu());
            }
            if (getInitialState().getMass().getReal() <= 0.0) {
                throw new OrekitException(OrekitMessages.SPACECRAFT_MASS_BECOMES_NEGATIVE,
                                               getInitialState().getMass());
            }

            // convert space flight dynamics API to math API
            final FieldSpacecraftState<T> initialIntegrationState = getInitialIntegrationState();
            final FieldODEState<T> mathInitialState = createInitialState(initialIntegrationState);
            final FieldExpandableODE<T> mathODE = createODE(integrator, mathInitialState);

            // mathematical integration
            final FieldODEStateAndDerivative<T> mathFinalState;
            beforeIntegration(initialIntegrationState, tEnd);
            mathFinalState = integrator.integrate(mathODE, mathInitialState,
                                                  tEnd.durationFrom(getInitialState().getDate()));

            afterIntegration();

            // get final state
            FieldSpacecraftState<T> finalState =
                            stateMapper.mapArrayToState(stateMapper.mapDoubleToDate(mathFinalState.getTime(), tEnd),
                                                        mathFinalState.getPrimaryState(),
                                                        mathFinalState.getPrimaryDerivative(),
                                                        propagationType);
            for (int i = 0; i < integrableGenerators.size(); ++i) {
                final T[] secondary = mathFinalState.getSecondaryState(i + 1);
                finalState = finalState.addAdditionalState(integrableGenerators.get(i).getName(),
                                                           secondary);
            }
            finalState = updateAdditionalStates(finalState);

            if (resetAtEnd) {
                resetInitialState(finalState);
                setStartDate(finalState.getDate());
            }

            return finalState;

        } catch (OrekitException pe) {
            throw pe;
        } catch (MathIllegalArgumentException miae) {
            throw OrekitException.unwrap(miae);
        } catch (MathIllegalStateException mise) {
            throw OrekitException.unwrap(mise);
        }
    }

    /** Get the initial state for integration.
     * @return initial state for integration
     */
    protected FieldSpacecraftState<T> getInitialIntegrationState() {
        return getInitialState();
    }

    /** Create an initial state.
     * @param initialState initial state in flight dynamics world
     * @return initial state in mathematics world
     */
    @SuppressWarnings("deprecation")
    private FieldODEState<T> createInitialState(final FieldSpacecraftState<T> initialState) {

        // retrieve initial state
        final T[] primary  = MathArrays.buildArray(initialState.getA().getField(), getBasicDimension());
        stateMapper.mapStateToArray(initialState, primary, null);

        // secondary part of the ODE
        final T[][] secondary = MathArrays.buildArray(initialState.getA().getField(), integrableGenerators.size(), -1);
        for (int i = 0; i < integrableGenerators.size(); ++i) {
            final FieldIntegrableGenerator<T> generator = integrableGenerators.get(i);
            final T[] addState = getInitialState().getAdditionalState(generator.getName());
            secondary[i] = MathArrays.buildArray(initialState.getA().getField(), addState.length);
            for (int j = 0; j < addState.length; j++) {
                secondary[i][j] = addState[j];
            }
            if (generator instanceof FieldAdditionalEquationAdapter) {
                ((FieldAdditionalEquationAdapter<T>) generator).setDimension(secondary[i].length);
            }
        }

        return new FieldODEState<>(initialState.getA().getField().getZero(), primary, secondary);

    }

    /** Create an ODE with all equations.
     * @param integ numerical integrator to use for propagation.
     * @param mathInitialState initial state
     * @return a new ode
     */
    private FieldExpandableODE<T> createODE(final FieldODEIntegrator<T> integ,
                                    final FieldODEState<T> mathInitialState) {

        final FieldExpandableODE<T> ode =
                new FieldExpandableODE<>(new ConvertedMainStateEquations(getMainStateEquations(integ)));

        // secondary part of the ODE
        for (int i = 0; i < integrableGenerators.size(); ++i) {
            final FieldIntegrableGenerator<T> generator = integrableGenerators.get(i);
            final FieldSecondaryODE<T>        secondary =
                    new ConvertedSecondaryStateEquations(generator,
                                                         mathInitialState.getSecondaryStateDimension(i + 1));
            ode.addSecondaryEquations(secondary);
        }

        return ode;

    }

    /** Method called just before integration.
     * <p>
     * The default implementation does nothing, it may be specialized in subclasses.
     * </p>
     * @param initialState initial state
     * @param tEnd target date at which state should be propagated
     */
    protected void beforeIntegration(final FieldSpacecraftState<T> initialState,
                                     final FieldAbsoluteDate<T> tEnd) {
        // do nothing by default
    }

    /** Method called just after integration.
     * <p>
     * The default implementation does nothing, it may be specialized in subclasses.
     * </p>
     */
    protected void afterIntegration() {
        // do nothing by default
    }

    /** Get state vector dimension without additional parameters.
     * @return state vector dimension without additional parameters.
     */
    public int getBasicDimension() {
        return 7;

    }

    /** Get the integrator used by the propagator.
     * @return the integrator.
     */
    protected FieldODEIntegrator<T> getIntegrator() {
        return integrator;
    }

    /** Convert a state from mathematical world to space flight dynamics world.
     * @param os mathematical state
     * @return space flight dynamics state
     */
    private FieldSpacecraftState<T> convert(final FieldODEStateAndDerivative<T> os) {
        try {

            FieldSpacecraftState<T> s =
                    stateMapper.mapArrayToState(os.getTime(),
                                                os.getPrimaryState(),
                                                os.getPrimaryDerivative(),
                                                propagationType);
            for (int i = 0; i < integrableGenerators.size(); ++i) {
                final T[] secondary = os.getSecondaryState(i + 1);
                s = s.addAdditionalState(integrableGenerators.get(i).getName(), secondary);
            }
            s = updateAdditionalStates(s);

            return s;

        } catch (OrekitException oe) {
            throw new OrekitException(oe);
        }
    }

    /** Convert a state from space flight dynamics world to mathematical world.
     * @param state space flight dynamics state
     * @return mathematical state
     */
    private FieldODEStateAndDerivative<T> convert(final FieldSpacecraftState<T> state) {

        // retrieve initial state
        final T[] primary    = MathArrays.buildArray(getField(), getBasicDimension());
        final T[] primaryDot = MathArrays.buildArray(getField(), getBasicDimension());
        stateMapper.mapStateToArray(state, primary, primaryDot);

        // secondary part of the ODE
        final T[][] secondary    = MathArrays.buildArray(getField(), integrableGenerators.size(), -1);
        for (int i = 0; i < integrableGenerators.size(); ++i) {
            final FieldIntegrableGenerator<T> generator = integrableGenerators.get(i);
            secondary[i] = state.getAdditionalState(generator.getName());
        }

        return new FieldODEStateAndDerivative<>(stateMapper.mapDateToDouble(state.getDate()),
                                                primary, primaryDot,
                                                secondary, null);

    }

    /** Differential equations for the main state (orbit, attitude and mass). */
    public interface MainStateEquations<T extends CalculusFieldElement<T>> {

        /**
         * Initialize the equations at the start of propagation. This method will be
         * called before any calls to {@link #computeDerivatives(FieldSpacecraftState)}.
         *
         * <p> The default implementation of this method does nothing.
         *
         * @param initialState initial state information at the start of propagation.
         * @param target       date of propagation. Not equal to {@code
         *                     initialState.getDate()}.
         */
        void init(FieldSpacecraftState<T> initialState, FieldAbsoluteDate<T> target);

        /** Compute differential equations for main state.
         * @param state current state
         * @return derivatives of main state
         */
        T[] computeDerivatives(FieldSpacecraftState<T> state);

    }

    /** Differential equations for the main state (orbit, attitude and mass), with converted API. */
    private class ConvertedMainStateEquations implements FieldOrdinaryDifferentialEquation<T> {

        /** Main state equations. */
        private final MainStateEquations<T> main;

        /** Simple constructor.
         * @param main main state equations
         */
        ConvertedMainStateEquations(final MainStateEquations<T> main) {
            this.main = main;
            calls = 0;
        }

        /** {@inheritDoc} */
        public int getDimension() {
            return getBasicDimension();
        }

        @Override
        public void init(final T t0, final T[] y0, final T finalTime) {
            // update space dynamics view
            FieldSpacecraftState<T> initialState = stateMapper.mapArrayToState(t0, y0, null, PropagationType.MEAN);
            initialState = updateAdditionalStates(initialState);
            final FieldAbsoluteDate<T> target = stateMapper.mapDoubleToDate(finalTime);
            main.init(initialState, target);
        }
        /** {@inheritDoc} */
        public T[] computeDerivatives(final T t, final T[] y) {

            // increment calls counter
            ++calls;

            // update space dynamics view
            FieldSpacecraftState<T> currentState = stateMapper.mapArrayToState(t, y, null, PropagationType.MEAN);
            currentState = updateAdditionalStates(currentState);

            // compute main state differentials
            return main.computeDerivatives(currentState);

        }

    }

    /** Differential equations for the secondary state (Jacobians, user variables ...), with converted API. */
    private class ConvertedSecondaryStateEquations implements FieldSecondaryODE<T> {

        /** Integrable generator. */
        private final FieldIntegrableGenerator<T> generator;

        /** Dimension of the additional state. */
        private final int dimension;

        /** Simple constructor.
         * @param generator differential equations in the form of an integrable generator
         * @param dimension dimension of the additional state
         */
        ConvertedSecondaryStateEquations(final FieldIntegrableGenerator<T> generator,
                                         final int dimension) {
            this.generator = generator;
            this.dimension = dimension;
        }

        /** {@inheritDoc} */
        @Override
        public int getDimension() {
            return dimension;
        }

        /** {@inheritDoc} */
        @Override
        public void init(final T t0, final T[] primary0,
                         final T[] secondary0, final T finalTime) {
            // update space dynamics view
            FieldSpacecraftState<T> initialState = stateMapper.mapArrayToState(t0, primary0, null, PropagationType.MEAN);
            initialState = initialState.addAdditionalState(generator.getName(), secondary0);
            initialState = updateAdditionalStates(initialState);
            final FieldAbsoluteDate<T> target = stateMapper.mapDoubleToDate(finalTime);
            generator.init(initialState, target);
        }

        /** {@inheritDoc} */
        @Override
        public T[] computeDerivatives(final T t, final T[] primary,
                                      final T[] primaryDot, final T[] secondary) {

            // update space dynamics view
            FieldSpacecraftState<T> currentState = stateMapper.mapArrayToState(t, primary, primaryDot, PropagationType.MEAN);
            currentState = currentState.addAdditionalState(generator.getName(), secondary);
            currentState = updateAdditionalStates(currentState);

            return currentState.getAdditionalStateDerivative(generator.getName());

        }

    }

    /** Adapt an {@link org.orekit.propagation.events.FieldEventDetector<T>}
     * to Hipparchus {@link org.hipparchus.ode.events.FieldODEEventHandler<T>} interface.
     * @param <T> class type for the generic version
     * @author Fabien Maussion
     */
    private class FieldAdaptedEventDetector implements FieldODEEventHandler<T> {

        /** Underlying event detector. */
        private final FieldEventDetector<T> detector;

        /** Time of the previous call to g. */
        private T lastT;

        /** Value from the previous call to g. */
        private T lastG;

        /** Build a wrapped event detector.
         * @param detector event detector to wrap
        */
        FieldAdaptedEventDetector(final FieldEventDetector<T> detector) {
            this.detector = detector;
            this.lastT    = getField().getZero().add(Double.NaN);
            this.lastG    = getField().getZero().add(Double.NaN);
        }

        /** {@inheritDoc} */
        public void init(final FieldODEStateAndDerivative<T> s0, final T t) {
            detector.init(convert(s0), stateMapper.mapDoubleToDate(t));
            this.lastT = getField().getZero().add(Double.NaN);
            this.lastG = getField().getZero().add(Double.NaN);
        }

        /** {@inheritDoc} */
        public T g(final FieldODEStateAndDerivative<T> s) {
            if (!Precision.equals(lastT.getReal(), s.getTime().getReal(), 0)) {
                lastT = s.getTime();
                lastG = detector.g(convert(s));
            }
            return lastG;
        }

        /** {@inheritDoc} */
        public Action eventOccurred(final FieldODEStateAndDerivative<T> s, final boolean increasing) {
            return detector.eventOccurred(convert(s), increasing);
        }

        /** {@inheritDoc} */
        public FieldODEState<T> resetState(final FieldODEStateAndDerivative<T> s) {

            final FieldSpacecraftState<T> oldState = convert(s);
            final FieldSpacecraftState<T> newState = detector.resetState(oldState);
            stateChanged(newState);

            // main part
            final T[] primary    = MathArrays.buildArray(getField(), s.getPrimaryStateDimension());
            stateMapper.mapStateToArray(newState, primary, null);

            // secondary part
            final T[][] secondary = MathArrays.buildArray(getField(), integrableGenerators.size(), -1);

            for (int i = 0; i < integrableGenerators.size(); ++i) {
                final FieldIntegrableGenerator<T> generator = integrableGenerators.get(i);
                final T[] si = newState.getAdditionalState(generator.getName());
                secondary[i] = MathArrays.buildArray(getField(), si.length);
                System.arraycopy(si, 0, secondary[i], 0, si.length);
            }

            return new FieldODEState<>(newState.getDate().durationFrom(getStartDate()),
                                       primary, secondary);
        }

    }

    /** Adapt an {@link org.orekit.propagation.sampling.FieldOrekitStepHandler<T>}
     * to Hipparchus {@link FieldODEStepHandler<T>} interface.
     * @author Luc Maisonobe
     */
    private class FieldAdaptedStepHandler implements FieldODEStepHandler<T> {

        /** Underlying handler. */
        private final FieldOrekitStepHandler<T> handler;

        /** Build an instance.
         * @param handler underlying handler to wrap
         */
        FieldAdaptedStepHandler(final FieldOrekitStepHandler<T> handler) {
            this.handler = handler;
        }

        /** {@inheritDoc} */
        public void init(final FieldODEStateAndDerivative<T> s0, final T t) {
            handler.init(convert(s0), stateMapper.mapDoubleToDate(t));
        }

        /** {@inheritDoc} */
        public void handleStep(final FieldODEStateInterpolator<T> interpolator) {
            handler.handleStep(new FieldAdaptedStepInterpolator(interpolator));
        }

        /** {@inheritDoc} */
        @Override
        public void finish(final FieldODEStateAndDerivative<T> finalState) {
            handler.finish(convert(finalState));
        }

    }

    /** Adapt an {@link org.orekit.propagation.sampling.FieldOrekitStepInterpolator<T>}
     * to Hipparchus {@link FieldODEStepInterpolator<T>} interface.
     * @author Luc Maisonobe
     */
    private class FieldAdaptedStepInterpolator implements FieldOrekitStepInterpolator<T> {

        /** Underlying raw rawInterpolator. */
        private final FieldODEStateInterpolator<T> mathInterpolator;

        /** Build an instance.
         * @param mathInterpolator underlying raw interpolator
         */
        FieldAdaptedStepInterpolator(final FieldODEStateInterpolator<T> mathInterpolator) {
            this.mathInterpolator = mathInterpolator;
        }

        /** {@inheritDoc}} */
        @Override
        public FieldSpacecraftState<T> getPreviousState() {
            return convert(mathInterpolator.getPreviousState());
        }

        /** {@inheritDoc}} */
        @Override
        public FieldSpacecraftState<T> getCurrentState() {
            return convert(mathInterpolator.getCurrentState());
        }

        /** {@inheritDoc}} */
        @Override
        public FieldSpacecraftState<T> getInterpolatedState(final FieldAbsoluteDate<T> date) {
            return convert(mathInterpolator.getInterpolatedState(date.durationFrom(getStartDate())));
        }

        /** Check is integration direction is forward in date.
         * @return true if integration is forward in date
         */
        public boolean isForward() {
            return mathInterpolator.isForward();
        }

        /** {@inheritDoc}} */
        @Override
        public FieldAdaptedStepInterpolator restrictStep(final FieldSpacecraftState<T> newPreviousState,
                                                         final FieldSpacecraftState<T> newCurrentState) {
            try {
                final AbstractFieldODEStateInterpolator<T> aosi = (AbstractFieldODEStateInterpolator<T>) mathInterpolator;
                return new FieldAdaptedStepInterpolator(aosi.restrictStep(convert(newPreviousState),
                                                                          convert(newCurrentState)));
            } catch (ClassCastException cce) {
                // this should never happen
                throw new OrekitInternalError(cce);
            }
        }

    }

    /** Specialized step handler storing interpolators for ephemeris generation.
     * @since 11.0
     */
    private class FieldStoringStepHandler implements FieldODEStepHandler<T>, FieldEphemerisGenerator<T> {

        /** Underlying raw mathematical model. */
        private FieldDenseOutputModel<T> model;

        /** the user supplied end date. Propagation may not end on this date. */
        private FieldAbsoluteDate<T> endDate;

        /** Generated ephemeris. */
        private FieldBoundedPropagator<T> ephemeris;

        /** Set the end date.
         * @param endDate end date
         */
        public void setEndDate(final FieldAbsoluteDate<T> endDate) {
            this.endDate = endDate;
        }

        /** {@inheritDoc} */
        @Override
        public void init(final FieldODEStateAndDerivative<T> s0, final T t) {
            this.model = new FieldDenseOutputModel<>();
            model.init(s0, t);

            // ephemeris will be generated when last step is processed
            this.ephemeris = null;

        }

        /** {@inheritDoc} */
        @Override
        public FieldBoundedPropagator<T> getGeneratedEphemeris() {
            return ephemeris;
        }

        /** {@inheritDoc} */
        @Override
        public void handleStep(final FieldODEStateInterpolator<T> interpolator) {
            model.handleStep(interpolator);
        }

        /** {@inheritDoc} */
        @Override
        public void finish(final FieldODEStateAndDerivative<T> finalState) {

            // set up the boundary dates
            final T tI = model.getInitialTime();
            final T tF = model.getFinalTime();
            // tI is almost? always zero
            final FieldAbsoluteDate<T> startDate =
                            stateMapper.mapDoubleToDate(tI);
            final FieldAbsoluteDate<T> finalDate =
                            stateMapper.mapDoubleToDate(tF, this.endDate);
            final FieldAbsoluteDate<T> minDate;
            final FieldAbsoluteDate<T> maxDate;
            if (tF.getReal() < tI.getReal()) {
                minDate = finalDate;
                maxDate = startDate;
            } else {
                minDate = startDate;
                maxDate = finalDate;
            }

            // get the initial additional states that are not managed
            final FieldArrayDictionary<T> unmanaged = new FieldArrayDictionary<>(startDate.getField());
            for (final FieldArrayDictionary<T>.Entry initial : getInitialState().getAdditionalStatesValues().getData()) {
                if (!isAdditionalStateManaged(initial.getKey())) {
                    // this additional state was in the initial state, but is unknown to the propagator
                    // we simply copy its initial value as is
                    unmanaged.put(initial.getKey(), initial.getValue());
                }
            }

            // get the names of additional states managed by differential equations
            final String[] names = new String[integrableGenerators.size()];
            for (int i = 0; i < names.length; ++i) {
                names[i] = integrableGenerators.get(i).getName();
            }

            // create the ephemeris
            ephemeris = new FieldIntegratedEphemeris<>(startDate, minDate, maxDate,
                                                       stateMapper, propagationType, model,
                                                       unmanaged, getClosedFormGenerators(), names);

        }

    }

    /** Wrapper for resetting an integrator handlers.
     * <p>
     * This class is intended to be used in a try-with-resource statement.
     * If propagator-specific event handlers and step handlers are added to
     * the integrator in the try block, they will be removed automatically
     * when leaving the block, so the integrator only keep its own handlers
     * between calls to {@link AbstractIntegratedPropagator#propagate(AbsoluteDate, AbsoluteDate).
     * </p>
 * @param <T> the type of the field elements
     * @since 11.0
     */
    private static class IntegratorResetter<T extends CalculusFieldElement<T>> implements AutoCloseable {

        /** Wrapped integrator. */
        private final FieldODEIntegrator<T> integrator;

        /** Initial event handlers list. */
        private final List<FieldEventHandlerConfiguration<T>> eventHandlersConfigurations;

        /** Initial step handlers list. */
        private final List<FieldODEStepHandler<T>> stepHandlers;

        /** Simple constructor.
         * @param integrator wrapped integrator
         */
        IntegratorResetter(final FieldODEIntegrator<T> integrator) {
            this.integrator                  = integrator;
            this.eventHandlersConfigurations = new ArrayList<>(integrator.getEventHandlersConfigurations());
            this.stepHandlers                = new ArrayList<>(integrator.getStepHandlers());
        }

        /** {@inheritDoc}
         * <p>
         * Reset event handlers and step handlers back to the initial list
         * </p>
         */
        @Override
        public void close() {

            // reset event handlers
            integrator.clearEventHandlers();
            eventHandlersConfigurations.forEach(c -> integrator.addEventHandler(c.getEventHandler(),
                                                                                c.getMaxCheckInterval(),
                                                                                c.getConvergence().getReal(),
                                                                                c.getMaxIterationCount(),
                                                                                c.getSolver()));

            // reset step handlers
            integrator.clearStepHandlers();
            stepHandlers.forEach(stepHandler -> integrator.addStepHandler(stepHandler));

        }

    }

}
