// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.subsystems;

import com.revrobotics.AbsoluteEncoder;
import com.revrobotics.CANSparkMax;
import com.revrobotics.CANSparkMax.IdleMode;
import com.revrobotics.CANSparkMaxLowLevel.MotorType;
import com.revrobotics.SparkMaxAbsoluteEncoder.Type;

import edu.wpi.first.math.Nat;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.controller.LinearQuadraticRegulator;
import edu.wpi.first.math.estimator.KalmanFilter;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N2;
import edu.wpi.first.math.system.LinearSystem;
import edu.wpi.first.math.system.LinearSystemLoop;
import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.math.system.plant.LinearSystemId;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj2.command.SubsystemBase;


public class Elbow extends SubsystemBase {
  private CANSparkMax m_rightMotor;
  private CANSparkMax m_leftMotor;
  private AbsoluteEncoder m_encoder;

  private double kGearRatio = 240.0;
  private double kKinematicOffset = 630.0;
  private double kMomentOfInertia = 2; // kg

  private final TrapezoidProfile.Constraints m_constraints =
      new TrapezoidProfile.Constraints(
          Units.degreesToRadians(45),
          Units.degreesToRadians(90)); // Max arm speed and acceleration.

  private TrapezoidProfile.State m_lastProfiledReference = new TrapezoidProfile.State();
  private TrapezoidProfile.State m_goal = new TrapezoidProfile.State();


  // The plant holds a state-space model of our arm. This system has the following properties:
  //
  // States: [position, velocity], in radians and radians per second.
  // Inputs (what we can "put in"): [voltage], in volts.
  // Outputs (what we can measure): [position], in radians.
  private final LinearSystem<N2, N1, N1> m_armPlant =
      LinearSystemId.createSingleJointedArmSystem(DCMotor.getNEO(2), kMomentOfInertia, kGearRatio);

  // The observer fuses our encoder data and voltage inputs to reject noise.
  private final KalmanFilter<N2, N1, N1> m_observer =
      new KalmanFilter<>(
          Nat.N2(),
          Nat.N1(),
          m_armPlant,
          VecBuilder.fill(0.015, 0.17), // How accurate we
          // think our model is, in radians and radians/sec
          VecBuilder.fill(0.01), // How accurate we think our encoder position
          // data is. In this case we very highly trust our encoder position reading.
          0.020);

  // A LQR uses feedback to create voltage commands.
  private final LinearQuadraticRegulator<N2, N1, N1> m_controller =
      new LinearQuadraticRegulator<>(
          m_armPlant,
          VecBuilder.fill(Units.degreesToRadians(1.0), Units.degreesToRadians(10.0)), // qelms.
          // Position and velocity error tolerances, in radians and radians per second. Decrease
          // this
          // to more heavily penalize state excursion, or make the controller behave more
          // aggressively. In this example we weight position much more highly than velocity, but
          // this
          // can be tuned to balance the two.
          VecBuilder.fill(12.0), // relms. Control effort (voltage) tolerance. Decrease this to more
          // heavily penalize control effort, or make the controller less aggressive. 12 is a good
          // starting point because that is the (approximate) maximum voltage of a battery.
          0.020); // Nominal time between loops. 0.020 for TimedRobot, but can be
  // lower if using notifiers.

  // The state-space loop combines a controller, observer, feedforward and plant for easy control.
  private final LinearSystemLoop<N2, N1, N1> m_loop =
      new LinearSystemLoop<>(m_armPlant, m_controller, m_observer, 12.0, 0.020);

  /** Creates a new Shoulder. */
  public Elbow() {
    m_leftMotor = new CANSparkMax(11, MotorType.kBrushless);
    m_rightMotor = new CANSparkMax(12, MotorType.kBrushless);
    m_leftMotor.follow(m_rightMotor, true);
    m_rightMotor.setInverted(true); //must be inverted
    m_rightMotor.setIdleMode(IdleMode.kBrake);
    m_leftMotor.setIdleMode(IdleMode.kBrake);
    m_rightMotor.setSmartCurrentLimit(30);
    m_leftMotor.setSmartCurrentLimit(30);

    m_encoder = m_rightMotor.getAbsoluteEncoder(Type.kDutyCycle);
    m_encoder.setPositionConversionFactor((2*Math.PI));
    m_encoder.setInverted(false); //must be inverted
    m_encoder.setZeroOffset(230.2364949);
    //todo set velocity conversion factor

    m_rightMotor.burnFlash();
    m_leftMotor.burnFlash();

    // Reset our loop to make sure it's in a known state.
    m_loop.reset(VecBuilder.fill(m_encoder.getPosition(), m_encoder.getVelocity()));

    // Reset our last reference to the current state.
    m_lastProfiledReference =
        new TrapezoidProfile.State(m_encoder.getPosition(), m_encoder.getVelocity());

  }

  public double convertTicksToKinematicAngle(double encoderPosition) {
    return (encoderPosition - kKinematicOffset) / kGearRatio;
  }

  public double convertKinematicAngleToTicks(double angleRadians) {
    return (angleRadians * kGearRatio) + kKinematicOffset;
  }

  public double getKinematicAngle() {
    return convertTicksToKinematicAngle(m_encoder.getPosition());
  }

  public void setTargetKinematicAngleRadians(double targetAngleRadians) {
    m_goal = new TrapezoidProfile.State(convertKinematicAngleToTicks(targetAngleRadians), 0);
  }

  private void setCalculatedVoltage() {
    // Step our TrapezoidalProfile forward 20ms and set it as our next reference
    m_lastProfiledReference =
        (new TrapezoidProfile(m_constraints, m_goal, m_lastProfiledReference)).calculate(0.020);
    m_loop.setNextR(m_lastProfiledReference.position, m_lastProfiledReference.velocity);

    // Correct our Kalman filter's state vector estimate with encoder data.
    m_loop.correct(VecBuilder.fill(m_encoder.getPosition()));

    // Update our LQR to generate new voltage commands and use the voltages to predict the next
    // state with out Kalman filter.
    m_loop.predict(0.020);

    // Send the new calculated voltage to the motors.
    // voltage = duty cycle * battery voltage, so
    // duty cycle = voltage / battery voltage
    double nextVoltage = m_loop.getU(0);
    SmartDashboard.putNumber("next voltage", nextVoltage);
    m_rightMotor.setVoltage(nextVoltage);
  }

  @Override
  public void periodic() {
    setCalculatedVoltage();
    SmartDashboard.putNumber("Target Angle", convertTicksToKinematicAngle(m_goal.position));
    SmartDashboard.putNumber("Shoulder Kinematic Angle", Units.radiansToDegrees(getKinematicAngle()));
  }
}