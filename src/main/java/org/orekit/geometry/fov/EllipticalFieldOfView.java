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
package org.orekit.geometry.fov;

import org.hipparchus.RealFieldElement;
import org.hipparchus.analysis.differentiation.DSFactory;
import org.hipparchus.analysis.differentiation.DerivativeStructure;
import org.hipparchus.geometry.euclidean.threed.FieldVector3D;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.util.FastMath;
import org.hipparchus.util.SinCos;
import org.orekit.propagation.events.VisibilityTrigger;

/** Class representing a spacecraft sensor Field Of View with elliptical shape.
 * <p>
 * There are several ways to define an elliptical shape on the unit sphere.
 * </p>
 * <p>
 * Without loss of generality, one can assume that with a suitable rotation
 * the ellipse center is along the Z<sub>ell</sub> axis and the ellipse principal axes
 * are along the X<sub>ell</sub> and Y<sub>ell</sub> axes. The first defining
 * elements for an ellipse are these canonical axes. This class allows specifying
 * them by giving directly the Z<sub>ell</sub> axis as the {@link center} of
 * the ellipse, and giving a {@code primaryMeridian} vector in the (+X<sub>ell</sub>,
 * Z<sub>ell</sub>) half-plane. It is allowed to have {@code primaryMeridian} not
 * orthogonal to {@code center} as orthogonality will be fixed internally.
 * </p>
 * <p>
 * We can define angular coordinates \((\alpha, \beta)\) as dihedra angles around the
 * +Y<sub>ell</sub> and -X<sub>ell</sub> axes respectively to specify points on the
 * unit sphere. The corresponding Cartesian coordinates will be
 * \[P_{\alpha,\beta}\left(\begin{gather*}
 *   \frac{\sin\alpha\cos\beta}{\sqrt{1-\sin^2\alpha\sin^2\beta}}\\
 *   \frac{\cos\alpha\sin\beta}{\sqrt{1-\sin^2\alpha\sin^2\beta}}\\
 *   \frac{\cos\alpha\cos\beta}{\sqrt{1-\sin^2\alpha\sin^2\beta}}
 * \end{gather*}\right)\]
 * which shows that angle \(\beta=0\) corresponds to the (X<sub>ell</sub>, Z<sub>ell</sub>)
 * plane and that angle \(\alpha=0\) corresponds to the (Y<sub>ell</sub>, Z<sub>ell</sub>)
 * plane. Note that at least one of the angles must be different from \(\pm\frac{\pi}{2}\),
 * which means that the expression above is singular for points in the (X<sub>ell</sub>,
 * Y<sub>ell</sub>) plane.
 * </p>
 * <p>
 * The size of the ellipse is defined by its half aperture angles \(\lambda\) along the
 * X<sub>ell</sub> axis and \(\mu\) along the Y<sub>ell</sub> axis.
 * For points belonging to the ellipse, we always have \(-\lambda \le \alpha \le +\lambda\)
 * and \(-\mu \le \beta \le +\mu\), equalities being reached at the end of principal axes.
 * An ellipse defined on the sphere is not a planar ellipse because the four endpoints
 * \((\alpha=\pm\lambda, \beta=0)\) and \((\alpha=0, \beta=\pm\mu)\) are not coplanar
 * when \(\lambda\neq\mu\).
 * </p>
 * <p>
 * We define an ellipse on the sphere as the locus of points \(P\) such that the sum of
 * their angular distance to two foci \(F_+\) and \(F_-\) is constant, all points being on
 * the sphere. The relationship between the foci and the two half aperture angles \(\lambda\)
 * and \(\mu\) are:
 * \[F_\pm\left(\begin{gather*}
 *   \pm\sin\delta\\
 *   0\\
 *   \cos\delta
 * \end{gather*}\right)
 * \quad\text{with}\quad
 * \cos\delta = \frac{\cos\lambda}{\cos\mu}\]
 * </p>
 * <p>
 * It can be shown that the previous definition is equivalent to define first a regular
 * planar ellipse drawn on a plane \(z = z_0\) (\(z_0\) being an arbitrary strictly positive
 * number, \(z_0=1\) being the simplest choice) with semi major axis \(a=z_0\tan\lambda\)
 * and semi minor axis \(b=z_0\tan\mu\) and then to project it onto the sphere using a
 * central projection:
 * \[\left\{\begin{align*}
 * \left(\frac{x}{z_0\tan\lambda}\right)^2 + \left(\frac{y}{z_0\tan\mu}\right)^2 &amp;= \left(\frac{z}{z_0}\right)^2\\
 * x^2 + y^2 + z^2 &amp;= 1
 * \end{align*}\right.\]
 * </p>
 * <p>
 * Simplifying first equation by \(z_0\) and eliminating \(z^2\) in it using the second equation gives:
 * \[\left\{\begin{align*}
 * \left(\frac{x}{\sin\lambda}\right)^2 + \left(\frac{y}{\sin\mu}\right)^2 &amp;= 1\\
 * x^2 + y^2 + z^2 &amp;= 1
 * \end{align*}\right.\]
 * which shows that the previous definition is also equivalent to define first a
 * dimensionless planar ellipse on the \((x, y)\) plane and to project it onto the sphere
 * using a projection along \(z\).
 * </p>
 * <p>
 * Note however that despite the ellipse on the sphere can be computed as a projection
 * of an ellipse on the \((x, y)\) plane, the foci of one ellipse are not the projection of the
 * foci of the other ellipse. The foci on the plane are closer to each other by a factor
 * \(\cos\mu\) than the projection of the foci \(F_+\) and \(F_-\)).
 * </p>
 * @author Luc Maisonobe
 * @since 10.1
 */
public class EllipticalFieldOfView extends SmoothFieldOfView {

    /** Factory for derivatives. */
    private static final DSFactory FACTORY = new DSFactory(1, 2);

    /** FOV half aperture angle for spreading along X (i.e. rotation around +Y). */
    private final double halfApertureAlongX;

    /** FOV half aperture angle for spreading along Y (i.e. rotation around -X). */
    private final double halfApertureAlongY;

    /** tan(halfApertureAlongX). */
    private final double   tanX;

    /** tan(halfApertureAlongX). */
    private final double   tanY;

    /** First focus. */
    private final Vector3D focus1;

    /** Second focus. */
    private final Vector3D focus2;

    /** Cross product of focii. */
    private final Vector3D crossF1F2;

    /** Dot product of focii. */
    private final double dotF1F2;

    /** Scaling factor for normalizing ellipse points. */
    private final double d;

    /** Angular semi major axis. */
    private double a;

    /** Unit vector along major axis. */
    private final Vector3D u;

    /** Build a new instance.
     * <p>
     * Using a suitable rotation, an elliptical Field Of View can be oriented such
     * that the ellipse center is along the Z<sub>ell</sub> axis, one of its principal
     * axes is in the (X<sub>ell</sub>, Z<sub>ell</sub>) plane and the other principal
     * axis is in the (Y<sub>ell</sub>, Z<sub>ell</sub>) plane. Beware that the ellipse
     * principal axis that spreads along the Y<sub>ell</sub> direction corresponds to a
     * rotation around -X<sub>ell</sub> axis and that the ellipse principal axis that
     * spreads along the X<sub>ell</sub> direction corresponds to a rotation around
     * +Y<sub>ell</sub> axis. The naming convention used here is that the angles are
     * named after the spreading axis.
     * </p>
     * @param center direction of the FOV center (i.e. Z<sub>ell</sub>),
     * in spacecraft frame
     * @param primaryMeridian vector defining the (+X<sub>ell</sub>, Z<sub>ell</sub>)
     * half-plane (it is allowed to have {@code primaryMeridian} not orthogonal to
     * {@code center} as orthogonality will be fixed internally)
     * @param halfApertureAlongX FOV half aperture angle defining the ellipse spreading
     * along X<sub>ell</sub> (i.e. it corresponds to a rotation around +Y<sub>ell</sub>)
     * @param halfApertureAlongY FOV half aperture angle defining the ellipse spreading
     * along Y<sub>ell</sub> (i.e. it corresponds to a rotation around -X<sub>ell</sub>)
     * @param margin angular margin to apply to the zone (if positive,
     * the Field Of View will consider points slightly outside of the
     * zone are still visible)
     */
    public EllipticalFieldOfView(final Vector3D center, final Vector3D primaryMeridian,
                                 final double halfApertureAlongX, final double halfApertureAlongY,
                                 final double margin) {

        super(center, primaryMeridian, margin);
        this.halfApertureAlongX = halfApertureAlongX;
        this.halfApertureAlongY = halfApertureAlongY;
        this.tanX               = FastMath.tan(halfApertureAlongX);
        this.tanY               = FastMath.tan(halfApertureAlongY);
        if (halfApertureAlongX >= halfApertureAlongY) {
            final double cos = FastMath.cos(halfApertureAlongX) / FastMath.cos(halfApertureAlongY);
            final double sin = FastMath.sqrt(1 - cos * cos);
            focus1    = new Vector3D(+sin, getX(), cos, getZ());
            focus2    = new Vector3D(-sin, getX(), cos, getZ());
            crossF1F2 = new Vector3D(-2 * sin * cos, getY());
            dotF1F2   = 2 * cos * cos - 1;
            d         = 1.0 / (1 - dotF1F2 * dotF1F2);
            a         = halfApertureAlongX;
            u         = getX();
        } else {
            final double cos = FastMath.cos(halfApertureAlongY) / FastMath.cos(halfApertureAlongX);
            final double sin = FastMath.sqrt(1 - cos * cos);
            focus1    = new Vector3D(+sin, getY(), cos, getZ());
            focus2    = new Vector3D(-sin, getY(), cos, getZ());
            crossF1F2 = new Vector3D(2 * sin * cos, getX());
            dotF1F2   = 2 * cos * cos - 1;
            d         = 1.0 / (1 - dotF1F2 * dotF1F2);
            a         = halfApertureAlongY;
            u         = getY();
        }

    }

    /** get the FOV half aperture angle for spreading along X<sub>ell</sub> (i.e. rotation around +Y<sub>ell</sub>).
     * @return FOV half aperture angle for spreading along X<sub>ell</sub> (i.e. rotation around +Y<sub>ell</sub>
     */
    public double getHalfApertureAlongX() {
        return halfApertureAlongX;
    }

    /** get the FOV half aperture angle for spreading along Y<sub>ell</sub> (i.e. rotation around -X<sub>ell</sub>).
     * @return FOV half aperture angle for spreading along Y<sub>ell</sub> (i.e. rotation around -X<sub>ell</sub>)
     */
    public double getHalfApertureAlongY() {
        return halfApertureAlongY;
    }

    /** Get first focus in spacecraft frame.
     * @return first focus in spacecraft frame
     */
    public Vector3D getFocus1() {
        return focus1;
    }

    /** Get second focus in spacecraft frame.
     * @return second focus in spacecraft frame
     */
    public Vector3D getFocus2() {
        return focus2;
    }

    /** {@inheritDoc} */
    @Override
    public double offsetFromBoundary(final Vector3D lineOfSight, final double angularRadius,
                                     final VisibilityTrigger trigger) {

        final Vector3D los = lineOfSight.normalize();
        final double side = Vector3D.dotProduct(los, crossF1F2);
        if (FastMath.abs(side) < 1.0e-12) {
            // the line of sight is almost along the major axis
            // offset is signed angle with respect to ellipse extreme point
            final double xy = Vector3D.dotProduct(los, u);
            final double z  = Vector3D.dotProduct(los, getZ());
            return FastMath.abs(FastMath.atan2(xy, z)) - a +
                   trigger.radiusCorrection(angularRadius) - getMargin();
        }

        // find an initial point on ellipse, that approximates closest point
        final double d10     = Vector3D.angle(los, focus1);
        final double d20     = Vector3D.angle(los, focus2);
        final double offset0 = 0.5 * (d10 - d20);

        // find closest ellipse point
        DerivativeStructure offset = FACTORY.variable(0, offset0);
        for (int i = 0; i < 100; i++) {

            // function that evaluates to zero when distances difference corresponds to closest ellipse point P
            // which occurs when the plane defined by line of sight and P is orthogonal to ellipse tangent
            final FieldVector3D<DerivativeStructure> p = directionAt(offset.add(a), offset.subtract(a).negate(), side);
            final DerivativeStructure yn = FieldVector3D.dotProduct(p.subtract(los), tangent(p));

            // perform one Halley's iteration
            final double f0 = yn.getValue();
            final double f1 = yn.getPartialDerivative(1);
            final double f2 = yn.getPartialDerivative(2);
            final double dx = 2 * f0 * f1 / (2 * f1 * f1 - f0 * f2);
            offset = offset.subtract(dx);

            // check convergence
            if (FastMath.abs(dx) < 1.0e-12) {
                break;
            }

        }

        final Vector3D closest = directionAt(a + offset.getReal(), a - offset.getReal(), side);

        // compute raw offset as an accurate signed angle
        final double rawOffset = FastMath.copySign(Vector3D.angle(los, closest), d10 + d20 - 2 * a);

        return rawOffset + trigger.radiusCorrection(angularRadius) - getMargin();

    }

    /** {@inheritDoc} */
    @Override
    protected Vector3D directionAt(final double angle) {
        final SinCos   sce  = FastMath.sinCos(angle);
        final Vector3D dEll = new Vector3D(tanX * sce.cos(), tanY * sce.sin(), 1.0).normalize();
        return new Vector3D(dEll.getX(), getX(), dEll.getY(), getY(), dEll.getZ(), getZ());
    }

    /** Get a direction from distances to focii.
     * <p>
     * if {@code d1} + {@code d2} = 2 max({@link #getHalfApertureAlongX()}, {@link #getHalfApertureAlongY()}),
     * then the point is on the ellipse boundary
     * </p>
     * @param d1 distance to focus 1
     * @param d2 distance to focus 2
     * @param sign sign of the ellipse point with respect to F1 ^ F2
     * @return direction
     */
    private Vector3D directionAt(final double d1, final double d2, final double sign) {
        final double cos1 = FastMath.cos(d1);
        final double cos2 = FastMath.cos(d2);
        final double a1   = (cos1 - cos2 * dotF1F2) * d;
        final double a2   = (cos2 - cos1 * dotF1F2) * d;
        final double ac   = FastMath.sqrt((1 - (a1 * a1 + 2 * a1 * a2 * dotF1F2 + a2 * a2)) * d);
        return new Vector3D(a1, focus1, a2, focus2, FastMath.copySign(ac, sign), crossF1F2);
    }

    /** Get a direction from distances to focii.
     * <p>
     * if {@code d1} + {@code d2} = 2 max({@link #getHalfApertureAlongX()}, {@link #getHalfApertureAlongY()}),
     * then the point is on the ellipse boundary
     * </p>
     * @param d1 distance to focus 1
     * @param d2 distance to focus 2
     * @param sign sign of the ellipse point with respect to F1 ^ F2
     * @param <T> type of the field element
     * @return direction
     */
    private <T extends RealFieldElement<T>> FieldVector3D<T> directionAt(final T d1, final T d2, final double sign) {
        final T cos1 = FastMath.cos(d1);
        final T cos2 = FastMath.cos(d2);
        final T a1   = cos1.subtract(cos2.multiply(dotF1F2)).multiply(d);
        final T a2   = cos2.subtract(cos1.multiply(dotF1F2)).multiply(d);
        final T ac   = FastMath.sqrt(a1.multiply(a1.add(a2.multiply(2 * dotF1F2))).add(a2.multiply(a2)).multiply(d));
        return new FieldVector3D<>(a1, focus1, a2, focus2, FastMath.copySign(ac, sign), crossF1F2);
    }

    /** Get a vector tangent to the fov boundary.
     * <p>
     * Beware this method does <em>not</em> work at major axis ends.
     * </p>
     * @param direction normalized direction belonging to the fov boundary
     * @param <T> type of the field element
     * @return vector tangent to the fov boundary at {@code direction} in spacecraft frame
     */
    private <T extends RealFieldElement<T>> FieldVector3D<T> tangent(final FieldVector3D<T> direction) {

        // see Ian Agol and Sergei Tabachnikov answers to mathoverflow question
        // Ellipses on spheres (and other surfaces)
        // https://mathoverflow.net/questions/173723/ellipses-on-spheres-and-other-surfaces
        final FieldVector3D<T> geod1 = geodetic(focus1, direction);
        final FieldVector3D<T> geod2 = geodetic(focus2, direction);
        final FieldVector3D<T> t     = geod1.subtract(geod2).normalize();

        // fix tangent direction according to ellipse side
        if (FieldVector3D.dotProduct(direction, getY()).getReal() *
            FieldVector3D.dotProduct(t, getX()).getReal() < 0) {
            return t;
        } else {
            return t.negate();
        }

    }

    /** Compute tangent vector to the geodetic from focus to direction.
     * @param focus focus point
     * @param direction normalized direction belonging to the fov boundary
     * @param <T> type of the field element
     * @return vector tangent to the geodetic from focus to direction
     */
    private <T extends RealFieldElement<T>> FieldVector3D<T> geodetic(final Vector3D focus,
                                                                      final FieldVector3D<T> direction) {

        final T fd  = FieldVector3D.dotProduct(direction, focus);
        final T inv = fd.multiply(fd).negate().add(1).sqrt().reciprocal();
        return new FieldVector3D<>(fd.multiply(direction.getX()).subtract(focus.getX()).multiply(inv),
                                   fd.multiply(direction.getY()).subtract(focus.getY()).multiply(inv),
                                   fd.multiply(direction.getZ()).subtract(focus.getZ()).multiply(inv));

    }

}
