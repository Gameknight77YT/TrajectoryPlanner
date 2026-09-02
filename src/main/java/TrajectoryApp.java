import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.ScatterChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import org.orekit.orbits.KeplerianOrbit;
import org.orekit.orbits.PositionAngleType;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;

import org.orekit.estimation.iod.IodLambert;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.orbits.Orbit;
import org.orekit.utils.PVCoordinates;

import java.io.File;
import java.io.FileReader;
import java.util.*;

public class TrajectoryApp extends Application {

    public static class CelestialBody {
        String name;
        String parent;
        double mu;
        double radius;
        double sma;
        double eccentricity;
        double inclination;
        double atmosphere_height;
        String color; // NEW: Hex color from JSON
        public double period;
    }

    public static class TrajectoryResult {
        private final boolean isRoundTrip;
        private final boolean isGravityAssist;
        private final String routeName;
        private final double[] gaChromosome;
        private final List<CelestialBody> gaRoute;

        private final int depDayTotal;
        private final String depDateFormatted;
        private final int tofDays;
        private final double dv1, dv2, dv3, dv4;
        private final int stayDays, returnTofDays;
        private final Orbit transferOrbit, returnOrbit;
        private final AbsoluteDate depDate, arrDate, returnDepDate, returnArrDate;


        private double outboundDv, returnDv, totalDv;

        // NEW: Separate Ejection and Capture data
        private String ejectMoon = "None", capMoon = "None";
        private double ejectShift = 0.0, capShift = 0.0;
        private double ejectSave = 0.0, capSave = 0.0;

        public void applyAssists(VInfResult eject, VInfResult cap) {
            if (eject.found) {
                this.ejectMoon = eject.moonName;
                this.ejectShift = eject.shiftDays;
                this.ejectSave = eject.dvSavings;
                this.outboundDv -= eject.dvSavings;
                this.totalDv -= eject.dvSavings;
            }
            if (cap.found) {
                this.capMoon = cap.moonName;
                this.capShift = cap.shiftDays;
                this.capSave = cap.dvSavings;
                this.outboundDv -= cap.dvSavings;
                this.totalDv -= cap.dvSavings;
            }
            // Round to 1 decimal place and prevent negative fuel costs
            this.outboundDv = Math.max(0, Math.round(this.outboundDv * 10.0) / 10.0);
            this.totalDv = Math.max(0, Math.round(this.totalDv * 10.0) / 10.0);
        }

        // Format beautifully for the Table Columns
        public String getEjectStr() {
            return ejectMoon.equals("None") ? "-" : String.format("%s (-%.0f, %+.1fd)", ejectMoon, ejectSave, ejectShift);
        }
        public String getCapStr() {
            return capMoon.equals("None") ? "-" : String.format("%s (-%.0f, %+.1fd)", capMoon, capSave, capShift);
        }

        // 1-Way Constructor
        public TrajectoryResult(int depDayTotal, int year, int day, int tofDays, double dv1, double dv2, Orbit transferOrbit, AbsoluteDate depDate, AbsoluteDate arrDate) {
            this.isRoundTrip = false; this.isGravityAssist = false; this.routeName = "1-Way Direct";
            this.gaChromosome = null; this.gaRoute = null;
            this.depDayTotal = depDayTotal; this.depDateFormatted = String.format("Y%d, D%d", year, day);
            this.tofDays = tofDays; this.dv1 = dv1; this.dv2 = dv2;
            this.stayDays = 0; this.returnTofDays = 0; this.dv3 = 0; this.dv4 = 0;
            this.outboundDv = Math.round((dv1 + dv2) * 10.0) / 10.0; this.returnDv = 0.0; this.totalDv = this.outboundDv;
            this.transferOrbit = transferOrbit; this.returnOrbit = null;
            this.depDate = depDate; this.arrDate = arrDate; this.returnDepDate = null; this.returnArrDate = null;
        }

        // Round-Trip Constructor
        public TrajectoryResult(int depDayTotal, int year, int day, int tofDays, double dv1, double dv2, Orbit transferOrbit, AbsoluteDate depDate, AbsoluteDate arrDate,
                                int stayDays, int returnTofDays, double dv3, double dv4, Orbit returnOrbit, AbsoluteDate returnDepDate, AbsoluteDate returnArrDate) {
            this.isRoundTrip = true; this.isGravityAssist = false; this.routeName = "Round-Trip";
            this.gaChromosome = null; this.gaRoute = null;
            this.depDayTotal = depDayTotal; this.depDateFormatted = String.format("Y%d, D%d", year, day);
            this.tofDays = tofDays; this.dv1 = dv1; this.dv2 = dv2;
            this.stayDays = stayDays; this.returnTofDays = returnTofDays; this.dv3 = dv3; this.dv4 = dv4;
            this.outboundDv = Math.round((dv1 + dv2) * 10.0) / 10.0; this.returnDv = Math.round((dv3 + dv4) * 10.0) / 10.0;
            this.totalDv = Math.round((this.outboundDv + this.returnDv) * 10.0) / 10.0;
            this.transferOrbit = transferOrbit; this.returnOrbit = returnOrbit;
            this.depDate = depDate; this.arrDate = arrDate; this.returnDepDate = returnDepDate; this.returnArrDate = returnArrDate;
        }

        // NEW: Gravity Assist Constructor
        public TrajectoryResult(String routeName, List<CelestialBody> gaRoute, double[] gaChromosome, int depDayTotal, int year, int day, int tofDays, double totalDv, AbsoluteDate depDate, AbsoluteDate arrDate) {
            this.isRoundTrip = false; this.isGravityAssist = true; this.routeName = routeName;
            this.gaRoute = gaRoute; this.gaChromosome = gaChromosome;
            this.depDayTotal = depDayTotal; this.depDateFormatted = String.format("Y%d, D%d", year, day);
            this.tofDays = tofDays; this.totalDv = Math.round(totalDv * 10.0) / 10.0;
            this.outboundDv = this.totalDv; // Map total to outbound for the table UI

            // Zero out unused direct-flight fields
            this.dv1 = 0; this.dv2 = 0; this.stayDays = 0; this.returnTofDays = 0; this.dv3 = 0; this.dv4 = 0; this.returnDv = 0;
            this.transferOrbit = null; this.returnOrbit = null;
            this.depDate = depDate; this.arrDate = arrDate; this.returnDepDate = null; this.returnArrDate = null;
        }

        public int getDepDayTotal() { return depDayTotal; }
        public String getDepDateFormatted() { return depDateFormatted; }
        public int getTofDays() { return tofDays; }
        public int getStayDays() { return stayDays; }
        public int getReturnTofDays() { return returnTofDays; }
        public double getOutboundDv() { return outboundDv; }
        public double getReturnDv() { return returnDv; }
        public double getTotalDv() { return totalDv; }

        public boolean isGravityAssist() { return isGravityAssist; }
        public String getRouteName() { return routeName; }
        public double[] getGaChromosome() { return gaChromosome; }
        public List<CelestialBody> getGaRoute() { return gaRoute; }
    }

    public class GeneticOptimizer {
        private final List<CelestialBody> route;
        private final double startAlt, endAlt;
        private final int startDayOffset, windowDays;
        private final double maxTof;

        private final int POPULATION_SIZE = 1000;
        private final int GENERATIONS = 200;
        private final double MUTATION_RATE = 0.15;

        public GeneticOptimizer(List<CelestialBody> route, double startAlt, double endAlt, int startDayOffset, int windowDays, double maxTof) {
            this.route = route;
            this.startAlt = startAlt;
            this.endAlt = endAlt;
            this.startDayOffset = startDayOffset;
            this.windowDays = windowDays;
            this.maxTof = maxTof;
        }

        // Chromosome: [DepartureDay, TOF_Leg1, TOF_Leg2, ...]
        public double[] run() {
            int numGenes = route.size();
            List<double[]> population = new ArrayList<>();

            // 1. Initialize Population with Smart Seeds

            CelestialBody firstPlanet = route.get(0);
            CelestialBody secondPlanet = route.get(1);
            boolean isFirstLegMoon = secondPlanet.parent != null && secondPlanet.parent.equals(firstPlanet.name);

            int seedCount = (int) (POPULATION_SIZE * 0.15); // 15% of population are smart seeds


            double moonPeriodDays = 0.0;
            if (isFirstLegMoon) {
                moonPeriodDays = secondPlanet.period / 21600.0;
            }

            for (int i = 0; i < seedCount; i++) {
                double[] chromosome = new double[numGenes];

                if (isFirstLegMoon) {
                    // Spread the seeds evenly across the Moon's orbital period
                    double sweepOffset = moonPeriodDays * ((double) i / seedCount);
                    chromosome[0] = (startDayOffset + (windowDays / 2.0)) + sweepOffset;
                    chromosome[1] = 0.58; // Hohmann transfer from LKO to Mun is ~14 hours (0.58 days)
                } else {
                    chromosome[0] = startDayOffset + (Math.random() * windowDays);
                    chromosome[1] = 20.0 + (Math.random() * Math.max(10.0, (maxTof - 20.0)));
                }

                for (int j = (isFirstLegMoon ? 2 : 1); j < numGenes; j++) {
                    chromosome[j] = 20.0 + (Math.random() * Math.max(10.0, (maxTof - 20.0)));
                }
                population.add(chromosome);
            }

            // Step B: Fill the rest with pure random chaos to maintain genetic diversity
            for (int i = seedCount; i < POPULATION_SIZE; i++) {
                double[] chromosome = new double[numGenes];
                chromosome[0] = startDayOffset + (Math.random() * windowDays);

                for (int j = 1; j < numGenes; j++) {
                    CelestialBody prev = route.get(j - 1);
                    CelestialBody next = route.get(j);
                    boolean isLocalTransit = next.parent != null &&
                            (next.parent.equals(prev.name) || (prev.parent != null && prev.parent.equals(next.name)) || next.parent.equals(prev.parent));

                    if (isLocalTransit) {
                        chromosome[j] = 0.16 + (Math.random() * 10.0);
                    } else {
                        chromosome[j] = 20.0 + (Math.random() * Math.max(10.0, (maxTof - 20.0)));
                    }
                }
                population.add(chromosome);
            }

            // FIX 1: Provide a guaranteed fallback so it can never be null
            double[] bestChromosome = population.get(0).clone();
            double bestFitness = Double.MAX_VALUE;

            // 2. Evolution Loop
            for (int gen = 0; gen < GENERATIONS; gen++) {
                List<double[]> nextGen = new ArrayList<>();

                // Track best of this generation
                for (double[] chromo : population) {
                    double fitness = evaluate(chromo);
                    if (fitness < bestFitness) {
                        bestFitness = fitness;
                        bestChromosome = chromo.clone();
                    }
                }

                // Elitism: Keep the best one exactly as is
                nextGen.add(bestChromosome.clone());

                // Breed the rest
                while (nextGen.size() < POPULATION_SIZE) {
                    double[] parent1 = selectParent(population);
                    double[] parent2 = selectParent(population);
                    double[] child = crossoverAndMutate(parent1, parent2);
                    nextGen.add(child);
                }
                population = nextGen;
            }

            // --- RUN LAYER 3: LOCAL FINE-TUNING ---
            // If the GA failed to find a valid route, don't try to polish it
            if (bestFitness < Double.MAX_VALUE) {
                bestChromosome = optimizeLocally(bestChromosome);
                bestFitness = evaluate(bestChromosome);
            }

            // Format the final array and return it to the UI
            double[] result = Arrays.copyOf(bestChromosome, numGenes + 1);
            result[numGenes] = bestFitness;
            return result;
        }

        // Arithmetic Crossover & Gaussian Mutation
        private double[] crossoverAndMutate(double[] p1, double[] p2) {
            double[] child = new double[p1.length];
            for (int i = 0; i < p1.length; i++) {
                child[i] = (Math.random() > 0.5) ? p1[i] : p2[i]; // Blend parents

                // Proportional Mutation
                if (Math.random() < MUTATION_RATE) {
                    if (i == 0) {
                        child[i] += (Math.random() - 0.5) * 100.0; // Perturb departure by +/- 50 days
                    } else {
                        // Mutate by up to +/- 20% of the current transit time
                        double mutationAmount = Math.max(2.0, child[i] * 0.4);
                        child[i] += (Math.random() - 0.5) * mutationAmount;
                    }
                    child[i] = Math.max(0.1, child[i]); // Safe fractional floor
                }
            }
            return child;
        }

        // --- THE PHYSICS ENGINE EVALUATOR ---
        private double evaluate(double[] genes) {
            double fitness = 0.0;

            AbsoluteDate currentDate = KSP_EPOCH.shiftedBy(genes[0] * 21600.0);
            Vector3D vInfIn = null; // Incoming velocity from the previous leg

            for (int i = 0; i < route.size() - 1; i++) {
                CelestialBody bodyA = route.get(i);
                CelestialBody bodyB = route.get(i + 1);

                // NEW: Find the specific center of gravity for this exact leg
                CelestialBody legHCP = getCommonParent(bodyA, bodyB);
                IodLambert lambert = new IodLambert(legHCP.mu);

                double tof = genes[i + 1] * 21600.0;
                AbsoluteDate nextDate = currentDate.shiftedBy(tof);

                Vector3D pStart = getPVRelativeTo(bodyA, legHCP, currentDate).getPosition();
                Vector3D vPlanetStart = getPVRelativeTo(bodyA, legHCP, currentDate).getVelocity();

                // Prevent Lambert Singularity if starting exactly at the center of the HCP
                if (pStart.getNorm() < 1.0) {
                    pStart = new Vector3D(bodyA.radius + startAlt, 0, 0);
                }

                Vector3D pEnd = getPVRelativeTo(bodyB, legHCP, nextDate).getPosition();
                Vector3D vPlanetEnd = getPVRelativeTo(bodyB, legHCP, nextDate).getVelocity();

                try {
                    Orbit transfer = lambert.estimate(EME, true, 0, pStart, currentDate, pEnd, nextDate);
                    Vector3D vTransStart = transfer.getPVCoordinates(currentDate, EME).getVelocity();
                    Vector3D vTransEnd = transfer.getPVCoordinates(nextDate, EME).getVelocity();

                    // V-Infinity Out: How fast we leave body A relative to body A
                    Vector3D vInfOut = vTransStart.subtract(vPlanetStart);

                    if (i == 0) {
                        // First Leg: Calculate Departure Burn from Parking Orbit
                        fitness += calculateBurn(vTransStart, vPlanetStart, bodyA, startAlt);
                    } else {
                        // Intermediate Leg: Powered Flyby Penalty
                        // If the incoming vector doesn't perfectly match the outgoing vector required,
                        // the spacecraft must burn fuel to bend the trajectory.
                        fitness += vInfOut.subtract(vInfIn).getNorm();
                    }

                    if (i == route.size() - 2) {
                        // Final Leg: Calculate Arrival Burn into Parking Orbit
                        fitness += calculateBurn(vTransEnd, vPlanetEnd, bodyB, endAlt);
                    }

                    // Save incoming velocity (relative to body B) for the next leg's flyby penalty
                    vInfIn = vTransEnd.subtract(vPlanetEnd);

                    currentDate = nextDate;

                } catch (Exception e) {
                    return Double.MAX_VALUE; // Impossible orbit, kill this chromosome
                }
            }
            return fitness;
        }

        // --- LAYER 3: THE PHYSICS POLISHER ---
        private double[] optimizeLocally(double[] initialGuess) {
            double[] current = initialGuess.clone();
            double currentFitness = evaluate(current);

            // Assign custom step sizes: 5 days for planets, 0.1 days (2.4 hrs) for moons
            double[] stepSizes = new double[current.length];
            stepSizes[0] = 5.0;
            for (int i = 1; i < current.length; i++) {
                stepSizes[i] = (current[i] < 20.0) ? 0.1 : 5.0;
            }

            double minStep = 0.0001;
            boolean active = true;

            while (active) {
                active = false;
                boolean improved = false;

                for (int i = 0; i < current.length; i++) {
                    if (stepSizes[i] < minStep) continue; // This variable is fully polished
                    active = true;

                    double originalValue = current[i];

                    // 1. Step Forward
                    current[i] = originalValue + stepSizes[i];
                    double upFitness = evaluate(current);
                    if (upFitness < currentFitness) {
                        currentFitness = upFitness;
                        improved = true;
                        continue;
                    }

                    // 2. Step Backward
                    current[i] = originalValue - stepSizes[i];
                    if (current[i] < 0.1) current[i] = 0.1;

                    double downFitness = evaluate(current);
                    if (downFitness < currentFitness) {
                        currentFitness = downFitness;
                        improved = true;
                        continue;
                    }

                    // 3. Reset
                    current[i] = originalValue;
                }

                // If no variables improved, shrink all active step sizes by half
                if (!improved) {
                    for (int i = 0; i < stepSizes.length; i++) {
                        stepSizes[i] /= 2.0;
                    }
                }
            }
            return current;
        }

        // Tournament Selection
        private double[] selectParent(List<double[]> pop) {
            double[] best = pop.get((int) (Math.random() * pop.size()));
            double[] challenger = pop.get((int) (Math.random() * pop.size()));
            return evaluate(best) < evaluate(challenger) ? best : challenger;
        }
    }

    private Map<String, CelestialBody> bodiesMap = new HashMap<>();
    private TextArea outputArea;

    private TableView<TrajectoryResult> table;
    private ObservableList<TrajectoryResult> masterData = FXCollections.observableArrayList();
    private ScatterChart<Number, Number> porkchopChart;
    private Canvas canvas;
    private CelestialBody currentOrigin;
    private CelestialBody currentTarget;
    private CelestialBody currentHCP; // Highest Common Parent

    private AbsoluteDate KSP_EPOCH;
    private Frame EME;

    // Track origin and target for the drawing
    private Orbit currentOriginOrbit;
    private Orbit currentTargetOrbit;



    @Override
    public void init() {
        String absolutePath = "orekit-data";
        File orekitData = new File(absolutePath);

        if (!orekitData.exists()) {
            throw new RuntimeException("CRITICAL ERROR: The orekit-data folder does not exist at " + absolutePath);
        }

        // Load the data into Orekit FIRST
        DataContext.getDefault().getDataProvidersManager().addProvider(new DirectoryCrawler(orekitData));
        System.out.println("Orekit data successfully loaded!");

        // NOW it is safe to use TimeScalesFactory and FramesFactory
        KSP_EPOCH = new AbsoluteDate(2000, 1, 1, 12, 0, 0, TimeScalesFactory.getUTC());
        EME = FramesFactory.getEME2000();

        // Load KSP Bodies
        loadBodies();
    }

    // Generates a list of viable planetary sequences
    private List<List<CelestialBody>> findAssistSequences(CelestialBody origin, CelestialBody target, int maxAssists) {
        List<List<CelestialBody>> validRoutes = new ArrayList<>();
        List<CelestialBody> currentRoute = new ArrayList<>();
        currentRoute.add(origin);

        buildRouteDFS(currentRoute, target, maxAssists, validRoutes);
        return validRoutes;
    }

    private void buildRouteDFS(List<CelestialBody> currentRoute, CelestialBody target, int maxAssists,
                               List<List<CelestialBody>> validRoutes) {

        CelestialBody currentPlanet = currentRoute.get(currentRoute.size() - 1);
        CelestialBody origin = currentRoute.get(0);

        if (currentPlanet.equals(target)) {
            validRoutes.add(new ArrayList<>(currentRoute));
            return;
        }
        if (currentRoute.size() >= maxAssists + 2) return;

        for (CelestialBody nextPlanet : bodiesMap.values()) {
            if (nextPlanet.equals(currentPlanet) || nextPlanet.name.equals("Sun")) continue;
            if (currentRoute.contains(target)) continue;

            // --- CONTEXTUAL MOON FILTERING ---
            boolean isSunOrbiting = nextPlanet.parent.equals("Sun");
            boolean isMoonOfCurrent = nextPlanet.parent.equals(currentPlanet.name);
            boolean isSameSystem = nextPlanet.parent.equals(currentPlanet.parent) && !nextPlanet.parent.equals("Sun");

            // FIX: Ensure we don't accidentally treat the Sun as a local planetary system!
            boolean isOriginMoon = nextPlanet.parent.equals(origin.name) ||
                    (origin.parent != null && !origin.parent.equals("Sun") && nextPlanet.parent.equals(origin.parent));

            boolean isTargetMoon = nextPlanet.parent.equals(target.name) ||
                    (target.parent != null && !target.parent.equals("Sun") && nextPlanet.parent.equals(target.parent));

            // Discard moons that are strictly in the departure or arrival systems
            if (isOriginMoon || isTargetMoon) {
                continue;
            }

            // Discard any other moons that aren't relevant to our current mid-course position
            if (!isSunOrbiting && !isMoonOfCurrent && !isSameSystem) {
                continue;
            }


            // Heuristic: Prevent massive interplanetary jumps that waste assists
            if (isSunOrbiting && currentPlanet.parent.equals("Sun")) {
                double smaRatio = Math.max(currentPlanet.sma, nextPlanet.sma) / Math.min(currentPlanet.sma, nextPlanet.sma);
                if (smaRatio > 10.0 && !nextPlanet.equals(target)) continue;
            }

            currentRoute.add(nextPlanet);
            buildRouteDFS(currentRoute, target, maxAssists, validRoutes);
            currentRoute.remove(currentRoute.size() - 1);
        }
    }

    private void loadBodies() {
        try (FileReader reader = new FileReader("bodies.json")) {
            Gson gson = new Gson();
            Map<String, List<CelestialBody>> data = gson.fromJson(reader, new TypeToken<Map<String, List<CelestialBody>>>(){}.getType());
            for (CelestialBody b : data.get("bodies")) {
                bodiesMap.put(b.name, b);
            }

            // --- PRE-COMPUTE ORBITAL PERIODS ---
            for (CelestialBody body : bodiesMap.values()) {
                if (body.parent != null && bodiesMap.containsKey(body.parent)) {
                    double parentMu = bodiesMap.get(body.parent).mu;
                    // Kepler's Third Law: T = 2 * PI * sqrt(a^3 / mu)
                    body.period = 2.0 * Math.PI * Math.sqrt(Math.pow(body.sma, 3) / parentMu);
                } else {
                    body.period = 0.0; // The Sun has no orbital period
                }
            }
        } catch (Exception e) {
            System.err.println("Could not load bodies.json! Check file path.");
        }
    }

    private CelestialBody getCommonParent(CelestialBody b1, CelestialBody b2) {
        Set<String> ancestors = new HashSet<>();
        CelestialBody curr = b1;
        while (curr != null) {
            ancestors.add(curr.name);
            if (curr.name.equals("Sun")) break;
            curr = bodiesMap.get(curr.parent);
        }
        curr = b2;
        while (curr != null) {
            if (ancestors.contains(curr.name)) return curr;
            curr = bodiesMap.get(curr.parent);
        }
        return bodiesMap.get("Sun"); // Fallback
    }

    private PVCoordinates getPVRelativeTo(CelestialBody target, CelestialBody reference, AbsoluteDate date) {
        // If we've reached the reference point, distance is zero
        if (target == null || target.name.equals(reference.name)) {
            return new PVCoordinates(Vector3D.ZERO, Vector3D.ZERO);
        }

        CelestialBody parent = bodiesMap.get(target.parent);
        if (parent == null) return new PVCoordinates(Vector3D.ZERO, Vector3D.ZERO);

        // Get this body's orbit around its immediate parent
        Orbit localOrbit = getBodyOrbit(target, KSP_EPOCH, parent.mu, EME);
        PVCoordinates pvLocal = localOrbit.getPVCoordinates(date, EME);

        // Recursively add the parent's position (e.g. Tylo + Jool)
        PVCoordinates pvParent = getPVRelativeTo(parent, reference, date);

        return new PVCoordinates(
                pvParent.getPosition().add(pvLocal.getPosition()),
                pvParent.getVelocity().add(pvLocal.getVelocity())
        );
    }

    private void displayTrajectory(TrajectoryResult res) {
        StringBuilder sb = new StringBuilder("--- Mission Overview ---\n");
        sb.append("Route: ").append(res.getRouteName()).append("\n");
        sb.append(String.format("Departure Date: %s\n", res.getDepDateFormatted()));

        if (res.isGravityAssist()) {
            sb.append(String.format("Total Transit: %d KSP Days\n\n", res.getTofDays()));
            double[] chromo = res.getGaChromosome();
            for (int i = 1; i < chromo.length - 1; i++) {
                sb.append(String.format("Leg %d Transit: %.1f Days\n", i, chromo[i]));
            }
        } else {
            sb.append(String.format("Outbound Transit: %d KSP Days\n\n", res.getTofDays()));
            sb.append(String.format("Burn 1: %.1f m/s\nBurn 2: %.1f m/s\n", res.dv1, res.dv2));
        }
        if (!res.getEjectStr().equals("-")) {
            sb.append("\n--- Departure Assist ---\nLeveraging Moon: ").append(res.getEjectStr()).append("\n");
        }
        if (!res.getCapStr().equals("-")) {
            sb.append("\n--- Arrival Capture Assist ---\nBraking Moon: ").append(res.getCapStr()).append("\n");
        }

        sb.append(String.format("\nTOTAL DELTA-V: %.1f m/s", res.getTotalDv()));

    }


    private double calculateBurn(Vector3D vTransfer, Vector3D vPlanet, CelestialBody body, double parkAltKm) {
        double vInf = vTransfer.subtract(vPlanet).getNorm();
        double rPark = body.radius + (parkAltKm * 1000);
        return Math.sqrt(Math.pow(vInf, 2) + 2 * body.mu / rPark) - Math.sqrt(body.mu / rPark);
    }

    private void drawFlightPath(TrajectoryResult res) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        // Paint the background black
        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        double cx = canvas.getWidth() / 2.0;
        double cy = canvas.getHeight() / 2.0;

        // --- 1. FAILSAFES & SCALE CALCULATION ---
        if (currentHCP == null) currentHCP = bodiesMap.get("Sun"); // Failsafe for Reset View

        double maxDist = 0;
        List<CelestialBody> pathBodies = new ArrayList<>();
        double[] tofs = null;

        if (res != null) {
            maxDist = (currentOrigin != null) ? currentOrigin.sma : 0;
            if (currentTarget != null && currentTarget.sma > maxDist) maxDist = currentTarget.sma;

            if (res.isGravityAssist()) {
                pathBodies = res.getGaRoute();
                tofs = new double[pathBodies.size()];
                for (int i = 1; i < pathBodies.size(); i++) {
                    tofs[i] = res.getGaChromosome()[i] * 21600.0;
                    if (pathBodies.get(i).sma > maxDist) maxDist = pathBodies.get(i).sma;
                }
            } else {
                if (currentOrigin != null) pathBodies.add(currentOrigin);
                if (currentTarget != null) pathBodies.add(currentTarget);
                tofs = new double[] {0.0, res.getTofDays() * 21600.0};
            }
        } else {
            // Default Scale for "Reset View" (Zooms out to Jool)
            maxDist = bodiesMap.containsKey("Jool") ? bodiesMap.get("Jool").sma * 1.1 : 1E11;
        }

        if (maxDist <= 0) maxDist = 1E11; // Absolute failsafe
        double scale = (Math.min(cx, cy) - 60) / maxDist;

        // Identify interacted bodies to highlight them
        Set<String> activeBodies = new HashSet<>();
        if (res != null) {
            for (CelestialBody b : pathBodies) activeBodies.add(b.name);
            for (CelestialBody b : bodiesMap.values()) {
                if (res.getEjectStr() != null && res.getEjectStr().contains(b.name)) activeBodies.add(b.name);
                if (res.getCapStr() != null && res.getCapStr().contains(b.name)) activeBodies.add(b.name);
            }
        }

        // --- 2. DRAW BACKGROUND SOLAR SYSTEM ---
        AbsoluteDate baseDate = (res != null) ? res.depDate : KSP_EPOCH;

        for (CelestialBody body : bodiesMap.values()) {
            if (body.name.equals(currentHCP.name)) continue;

            boolean isActive = activeBodies.contains(body.name);
            boolean isMoon = body.parent != null && !body.parent.equals("Sun");

            gc.setLineWidth(isActive ? 1.5 : 0.5);
            gc.setStroke(isActive ? Color.rgb(220, 220, 220, 0.7) : Color.rgb(80, 80, 80, 0.3));
            gc.setLineDashes(null);

            gc.beginPath();
            if (!isMoon) {
                // FIXED: Use Integer loop to prevent floating-point UI freezes!
                if (body.period > 0) {
                    int segments = 120;
                    double step = body.period / segments;
                    for (int j = 0; j <= segments; j++) {
                        double t = j * step;
                        try {
                            Vector3D pos = getPVRelativeTo(body, currentHCP, KSP_EPOCH.shiftedBy(t)).getPosition();
                            double x = cx + (pos.getX() * scale); double y = cy + (pos.getY() * scale);
                            if (j == 0) gc.moveTo(x, y); else gc.lineTo(x, y);
                        } catch (Exception ignored) {}
                    }
                }
            } else {
                try {
                    CelestialBody parentBody = bodiesMap.get(body.parent);
                    Vector3D parentPos = getPVRelativeTo(parentBody, currentHCP, baseDate).getPosition();
                    double px = cx + (parentPos.getX() * scale); double py = cy + (parentPos.getY() * scale);

                    double visualSma = body.sma * scale;
                    if (visualSma < 6) visualSma = 6;
                    if (visualSma > 25) visualSma = 25;

                    gc.strokeOval(px - visualSma, py - visualSma, visualSma * 2, visualSma * 2);
                } catch (Exception ignored) {}
            }
            gc.stroke();

            // Draw the planet/moon dot
            try {
                Vector3D pos = getPVRelativeTo(body, currentHCP, baseDate).getPosition();
                double bx = cx + (pos.getX() * scale); double by = cy + (pos.getY() * scale);

                if (isActive) {
                    gc.setFill(isMoon ? Color.AQUA : Color.WHITE);
                    gc.fillOval(bx - 3, by - 3, 6, 6);
                    gc.setFill(Color.WHITE);
                    gc.fillText(body.name, bx + 6, by - 6);
                } else {
                    gc.setFill(Color.rgb(100, 100, 100, 0.4));
                    gc.fillOval(bx - 1.5, by - 1.5, 3, 3);
                }
            } catch (Exception ignored) {}
        }

        // --- 3. DRAW TRAJECTORY ARCS (Only if a route is selected) ---
        if (res == null || pathBodies.size() < 2) return; // Stop drawing if it's just a Reset View

        gc.setLineWidth(2.5);
        AbsoluteDate currentDate = res.depDate;

        double originX = 0, originY = 0, targetX = 0, targetY = 0;
        double ejectNodeX = 0, ejectNodeY = 0, capNodeX = 0, capNodeY = 0;

        for (int i = 0; i < pathBodies.size() - 1; i++) {
            CelestialBody bodyA = pathBodies.get(i);
            CelestialBody bodyB = pathBodies.get(i + 1);
            CelestialBody legHCP = getCommonParent(bodyA, bodyB);

            double tof = tofs[i + 1];
            AbsoluteDate nextDate = currentDate.shiftedBy(tof);

            try {
                Vector3D pStart = getPVRelativeTo(bodyA, legHCP, currentDate).getPosition();
                Vector3D pEnd = getPVRelativeTo(bodyB, legHCP, nextDate).getPosition();

                Orbit transfer = res.isGravityAssist() ?
                        new IodLambert(legHCP.mu).estimate(EME, true, 0, pStart, currentDate, pEnd, nextDate) :
                        res.transferOrbit;

                gc.setStroke(i % 2 == 0 ? Color.CYAN : Color.MAGENTA);
                gc.beginPath();

                // FIXED: Use Integer loop for safe UI rendering!
                int segments = 150;
                double step = tof / segments;
                double prevX = 0, prevY = 0;

                for (int j = 0; j <= segments; j++) {
                    double t = j * step;
                    AbsoluteDate stepDate = currentDate.shiftedBy(t);
                    Vector3D legHcpPos = getPVRelativeTo(legHCP, currentHCP, stepDate).getPosition();
                    Vector3D craftPos = transfer.getPVCoordinates(stepDate, EME).getPosition();
                    Vector3D absolutePos = legHcpPos.add(craftPos);

                    double x = cx + (absolutePos.getX() * scale); double y = cy + (absolutePos.getY() * scale);

                    if (j == 0) {
                        gc.moveTo(x, y);
                        prevX = x; prevY = y;
                        if (i == 0) { originX = x; originY = y; }
                    } else {
                        gc.lineTo(x, y);
                        if (i == 0 && j == 2) { // Use the 2nd segment to calculate ejection vector
                            double dx = x - originX; double dy = y - originY;
                            double mag = Math.sqrt(dx*dx + dy*dy);
                            ejectNodeX = originX + (dx/mag) * 12;
                            ejectNodeY = originY + (dy/mag) * 12;
                        }
                        prevX = x; prevY = y;
                    }
                }
                gc.stroke();

                if (i == pathBodies.size() - 2) {
                    targetX = prevX; targetY = prevY;
                    Vector3D cPosBack = transfer.getPVCoordinates(nextDate.shiftedBy(-step), EME).getPosition();
                    Vector3D hcpBack = getPVRelativeTo(legHCP, currentHCP, nextDate.shiftedBy(-step)).getPosition();
                    double bx = cx + ((hcpBack.getX() + cPosBack.getX()) * scale);
                    double by = cy + ((hcpBack.getY() + cPosBack.getY()) * scale);

                    double dx = targetX - bx; double dy = targetY - by;
                    double mag = Math.sqrt(dx*dx + dy*dy);
                    capNodeX = targetX - (dx/mag) * 12;
                    capNodeY = targetY - (dy/mag) * 12;
                }

                if (i > 0) {
                    gc.setFill(Color.MEDIUMPURPLE);
                    gc.fillOval(originX - 4, originY - 4, 8, 8);
                    gc.setStroke(Color.WHITE); gc.setLineWidth(1.5); gc.setLineDashes(null);
                    gc.strokeOval(originX - 4, originY - 4, 8, 8);
                    gc.setFill(Color.WHITE);
                    gc.fillText("Assist Node: " + bodyA.name, originX + 8, originY - 8);
                }

                originX = prevX; originY = prevY;

            } catch (Exception ignored) {}
            currentDate = nextDate;
        }

        // --- 4. DRAW STYLIZED UI MANEUVER NODES ---
        gc.setLineWidth(1.5);
        gc.setLineDashes(3);

        if (ejectNodeX != 0) {
            gc.setStroke(Color.LIGHTGREEN);
            gc.strokeOval(ejectNodeX - 12 - (ejectNodeX - originX), ejectNodeY - 12 - (ejectNodeY - originY), 24, 24);

            gc.setFill(Color.ORANGE);
            gc.fillOval(ejectNodeX - 4.5, ejectNodeY - 4.5, 9, 9);
            gc.setStroke(Color.WHITE); gc.setLineDashes(null);
            gc.strokeOval(ejectNodeX - 4.5, ejectNodeY - 4.5, 9, 9);
            gc.setFill(Color.WHITE);
            gc.fillText("Ejection Burn", ejectNodeX + 8, ejectNodeY - 8);
        }

        if (capNodeX != 0) {
            gc.setStroke(Color.PINK);
            gc.setLineDashes(3);
            gc.strokeOval(targetX - 12, targetY - 12, 24, 24);

            gc.setFill(Color.RED);
            gc.fillOval(capNodeX - 4.5, capNodeY - 4.5, 9, 9);
            gc.setStroke(Color.WHITE); gc.setLineDashes(null);
            gc.strokeOval(capNodeX - 4.5, capNodeY - 4.5, 9, 9);
            gc.setFill(Color.WHITE);
            gc.fillText("Capture Burn", capNodeX + 8, capNodeY - 8);
        }
    }

    //Data container for the assist results
    public static class VInfResult {
        public boolean found = false;
        public String moonName = "None";
        public double shiftDays = 0.0;
        public double dvSavings = 0.0;
    }

    // Analyzes all moons for a planet and calculates the exact physical dV savings
    private VInfResult checkMoonAssist(AbsoluteDate date, Vector3D vPlanet, Vector3D vTrans, CelestialBody planet) {
        VInfResult bestResult = new VInfResult();

        // Un-normalized V-Infinity vector relative to the main planet
        Vector3D vInf = vTrans.subtract(vPlanet);
        Vector3D vInfNorm = vInf.normalize();

        for (CelestialBody moon : bodiesMap.values()) {
            if (moon.parent != null && moon.parent.equals(planet.name)) {

                double bestDotProduct = -1.0;
                AbsoluteDate bestAssistDate = date;
                double calculatedSavings = 0.0;

                // Sweep +/- half a moon orbit
                double searchRadiusDays = moon.period / 21600.0 / 2.0;
                for (double t = -searchRadiusDays; t <= searchRadiusDays; t += (1.0 / 24.0)) {
                    AbsoluteDate checkDate = date.shiftedBy(t * 21600.0);
                    try {
                        Vector3D vMoon = getPVRelativeTo(moon, planet, checkDate).getVelocity();
                        Vector3D vMoonNorm = vMoon.normalize();

                        // Use Math.abs() because we can pass in front OR behind the moon
                        double alignment = Math.abs(vInfNorm.dotProduct(vMoonNorm));

                        if (alignment > bestDotProduct) {
                            bestDotProduct = alignment;
                            bestAssistDate = checkDate;

                            // --- PHYSICS CALCULATION ---
                            // 1. Hyperbolic excess velocity relative to the moon
                            double vRel = vInf.subtract(vMoon).getNorm();

                            // 2. Safe periapsis: Moon's radius + 20,000 meters clearance
                            double rp = moon.radius + 20000.0;

                            // 3. Maximum deflection angle sin(delta/2)
                            double sinDeltaOver2 = moon.mu / (moon.mu + (rp * vRel * vRel));

                            // 4. Exact Delta-V vector change magnitude
                            calculatedSavings = 2.0 * vRel * sinDeltaOver2;
                        }
                    } catch (Exception ignored) {}
                }

                // If perfectly aligned AND this moon saves more than the previous best moon
                if (bestDotProduct > 0.95 && calculatedSavings > bestResult.dvSavings) {
                    bestResult.found = true;
                    bestResult.moonName = moon.name;
                    bestResult.shiftDays = bestAssistDate.durationFrom(date) / 21600.0;
                    bestResult.dvSavings = calculatedSavings;
                }
            }
        }
        return bestResult;
    }

    private double zoomLevel = 1.0;
    private double offsetX = 0.0;
    private double offsetY = 0.0;
    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    private TextArea overviewArea;

    @Override
    public void start(Stage stage) {
        stage.setTitle("KSP Mission Control");

        // --- Controls (Top) ---
        ComboBox<String> originCombo = new ComboBox<>(); originCombo.getItems().addAll(bodiesMap.keySet()); originCombo.setValue("Kerbin");
        ComboBox<String> targetCombo = new ComboBox<>(); targetCombo.getItems().addAll(bodiesMap.keySet()); targetCombo.setValue("Jool");

        ComboBox<String> tripType = new ComboBox<>(); tripType.getItems().addAll("1-Way", "Round-Trip"); tripType.setValue("1-Way");
        TextField stayTimeField = new TextField("30"); stayTimeField.setDisable(true);

        // NEW: Gravity Assist Controls
        TextField maxAssistsField = new TextField("0");
        CheckBox useOptimizerBox = new CheckBox("Enable Genetic Algorithm");

        TextField startAltField = new TextField("100"); TextField endAltField = new TextField("100");
        TextField startYearField = new TextField("1"); TextField startDayField = new TextField("1");
        TextField durationField = new TextField("1500"); TextField maxDVField = new TextField("5000");
        TextField maxTofField = new TextField("3000");
        Button calcBtn = new Button("Scan Trajectories");

        // NEW: Progress Bar
        ProgressBar progressBar = new ProgressBar(0.0);
        progressBar.setMaxWidth(Double.MAX_VALUE); // Make it stretch across the pane
        progressBar.setVisible(false);             // Hide it until we click scan

        GridPane controls = new GridPane(); controls.setPadding(new Insets(10)); controls.setVgap(10); controls.setHgap(5);
        controls.addRow(0, new Label("Origin:"), originCombo, new Label("Target:"), targetCombo);
        controls.addRow(1, new Label("Trip Type:"), tripType, new Label("Stay (Days):"), stayTimeField);
        controls.addRow(2, new Label("Solver:"), useOptimizerBox, new Label("Max Stops:"), maxAssistsField);
        controls.addRow(3, new Label("Start Alt:"), startAltField, new Label("End Alt:"), endAltField);
        controls.addRow(4, new Label("Start Y/D:"), startYearField, startDayField);
        controls.addRow(5, new Label("Window:"), durationField, new Label("Max dV:"), maxDVField);
        controls.addRow(6, new Label("Max TOF:"), maxTofField);

        // Add the button and progress bar to the grid
        controls.add(calcBtn, 0, 7, 4, 1);
        controls.add(progressBar, 0, 8, 4, 1);

        // --- Left Panel: Tabs ---
        TabPane leftTabs = new TabPane();

        // NEW: Real-time Route Filter
        TextField routeFilterField = new TextField();
        routeFilterField.setPromptText("Filter routes (e.g., Eve -> Duna)");

        table = new TableView<>();

        // --- NEW: Dynamic Row Coloring based on Max dV ---
        table.setRowFactory(tv -> new TableRow<TrajectoryResult>() {
            @Override
            protected void updateItem(TrajectoryResult item, boolean empty) {
                super.updateItem(item, empty);
                if (item == null || empty) {
                    setStyle("");
                } else {
                    double limit = Double.MAX_VALUE;
                    try {
                        limit = Double.parseDouble(maxDVField.getText());
                    } catch (NumberFormatException ignored) {}

                    if (item.getTotalDv() <= limit) {
                        // Under budget: Soft Green background
                        setStyle("-fx-control-inner-background: #c8e6c9; -fx-control-inner-background-alt: #c8e6c9;");
                    } else {
                        // Over budget: Soft Red background
                        setStyle("-fx-control-inner-background: #ffcdd2; -fx-control-inner-background-alt: #ffcdd2;");
                    }
                }
            }
        });

        // --- NEW: Table Selection Listener ---
        table.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                // When a row is clicked, update the text description and draw the map!
                displayTrajectory(newValue);
            }
        });

        TableColumn<TrajectoryResult, String> colRoute = new TableColumn<>("Route");
        colRoute.setCellValueFactory(new PropertyValueFactory<>("routeName"));
        colRoute.setPrefWidth(120);

        TableColumn<TrajectoryResult, String> colDate = new TableColumn<>("Depart");
        colDate.setCellValueFactory(new PropertyValueFactory<>("depDateFormatted"));

        TableColumn<TrajectoryResult, Integer> colTof = new TableColumn<>("Out (Days)");
        colTof.setCellValueFactory(new PropertyValueFactory<>("tofDays"));

        TableColumn<TrajectoryResult, Double> colOutDv = new TableColumn<>("Out dV");
        colOutDv.setCellValueFactory(new PropertyValueFactory<>("outboundDv"));

        TableColumn<TrajectoryResult, Integer> colStay = new TableColumn<>("Stay");
        colStay.setCellValueFactory(new PropertyValueFactory<>("stayDays"));
        colStay.setVisible(false);

        TableColumn<TrajectoryResult, Integer> colRetTof = new TableColumn<>("Ret (Days)");
        colRetTof.setCellValueFactory(new PropertyValueFactory<>("returnTofDays"));
        colRetTof.setVisible(false);

        TableColumn<TrajectoryResult, Double> colRetDv = new TableColumn<>("Ret dV");
        colRetDv.setCellValueFactory(new PropertyValueFactory<>("returnDv"));
        colRetDv.setVisible(false);

        TableColumn<TrajectoryResult, Double> colDv = new TableColumn<>("Total dV");
        colDv.setCellValueFactory(new PropertyValueFactory<>("totalDv"));

        TableColumn<TrajectoryResult, String> colEj = new TableColumn<>("Eject Assist");
        colEj.setCellValueFactory(new PropertyValueFactory<>("ejectStr"));

        TableColumn<TrajectoryResult, String> colCap = new TableColumn<>("Capture Assist");
        colCap.setCellValueFactory(new PropertyValueFactory<>("capStr"));

        // Add them to your table definition
        table.getColumns().addAll(colRoute, colDate, colEj, colCap, colTof, colOutDv, colStay, colRetTof, colRetDv, colDv);

        // Wrap masterData in a FilteredList for instant searching
        javafx.collections.transformation.FilteredList<TrajectoryResult> filteredData = new javafx.collections.transformation.FilteredList<>(masterData, p -> true);
        routeFilterField.textProperty().addListener((obs, oldVal, newVal) -> {
            filteredData.setPredicate(res -> {
                if (newVal == null || newVal.isEmpty()) return true;
                return res.getRouteName().toLowerCase().contains(newVal.toLowerCase());
            });
        });

        // Wrap in a SortedList to preserve column sorting while filtering
        javafx.collections.transformation.SortedList<TrajectoryResult> sortedData = new javafx.collections.transformation.SortedList<>(filteredData);
        sortedData.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sortedData);

        VBox tableContainer = new VBox(5, routeFilterField, table);
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        leftTabs.getTabs().add(new Tab("Data Table", tableContainer));

        // 2. Setup Porkchop Plot
        NumberAxis xAxis = new NumberAxis(); xAxis.setLabel("Departure Day");
        NumberAxis yAxis = new NumberAxis(); yAxis.setLabel("Time of Flight (Days)");
        porkchopChart = new ScatterChart<>(xAxis, yAxis);
        porkchopChart.setAnimated(false);

        // Save the tab to a variable so we can control it
        Tab porkchopTab = new Tab("Porkchop Plot", porkchopChart);
        leftTabs.getTabs().add(porkchopTab);

        // --- UI Dynamic Toggles ---

        // Listen to the Solver Engine checkbox
        useOptimizerBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            porkchopTab.setDisable(newVal); // Disable the tab if Genetic Algorithm is ON

            // If the user was looking at the Porkchop plot, kick them back to the Data Table
            if (newVal && leftTabs.getSelectionModel().getSelectedItem() == porkchopTab) {
                leftTabs.getSelectionModel().select(0);
            }
        });

        // --- NEW: UI Dynamic Toggles ---

        // Disable the Stay Time text field initially since "1-Way" is the default
        stayTimeField.setDisable(true);

        // Listen to the Dropdown and toggle the columns and text field
        tripType.valueProperty().addListener((obs, oldVal, newVal) -> {
            boolean isRoundTrip = newVal.equals("Round-Trip");

            // Toggle Table Columns
            colStay.setVisible(isRoundTrip);
            colRetTof.setVisible(isRoundTrip);
            colRetDv.setVisible(isRoundTrip);

            // Toggle the Stay Time input field
            stayTimeField.setDisable(!isRoundTrip);
        });

        // --- Right Panel: Diagram & Overview ---
        VBox rightPanel = new VBox(10);
        rightPanel.setPadding(new Insets(10));

        Button resetZoomBtn = new Button("Reset View");
        resetZoomBtn.setOnAction(e -> {
            zoomLevel = 1.0;
            offsetX = 0.0;
            offsetY = 0.0;
            TrajectoryResult sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) drawFlightPath(sel);
        });

        HBox canvasControls = new HBox(resetZoomBtn);
        canvasControls.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        canvas = new Canvas(450, 450);

        // Scroll wheel zooming
        canvas.setOnScroll(event -> {
            if (event.getDeltaY() > 0) {
                zoomLevel *= 1.1;
            } else if (event.getDeltaY() < 0) {
                zoomLevel /= 1.1;
            }
            zoomLevel = Math.max(0.01, Math.min(zoomLevel, 5000.0));

            TrajectoryResult sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) drawFlightPath(sel);
        });

        // NEW: Record mouse start position for dragging
        canvas.setOnMousePressed(event -> {
            lastMouseX = event.getX();
            lastMouseY = event.getY();
        });

        // NEW: Calculate drag distance and apply to offsets
        canvas.setOnMouseDragged(event -> {
            double deltaX = event.getX() - lastMouseX;
            double deltaY = event.getY() - lastMouseY;

            offsetX += deltaX;
            offsetY += deltaY;

            lastMouseX = event.getX();
            lastMouseY = event.getY();

            TrajectoryResult sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) drawFlightPath(sel);
        });

        // NEW: Click-to-focus on planets
        canvas.setOnMouseClicked(event -> {
            // Only trigger on a left-click, and ensure we didn't just finish dragging
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY && event.isStillSincePress()) {
                TrajectoryResult res = table.getSelectionModel().getSelectedItem();
                if (res == null) return;

                double w = canvas.getWidth(); double h = canvas.getHeight();
                double maxDist = getPVRelativeTo(currentTarget, currentHCP, res.arrDate).getPosition().getNorm();

                // Calculate current scale to find where the planets are currently drawn on screen
                double currentScale = ((w / 2.2) / maxDist) * zoomLevel;
                double cx = (w / 2) + offsetX; double cy = (h / 2) + offsetY;

                // Re-gather the family tree to check hitboxes
                Set<CelestialBody> targetBranch = new HashSet<>();
                CelestialBody curr = currentTarget;
                while (curr != null && curr != currentHCP) { targetBranch.add(curr); curr = bodiesMap.get(curr.parent); }

                List<CelestialBody> clickableBodies = new ArrayList<>(bodiesMap.values()); // Check all bodies just in case

                CelestialBody clickedBody = null;
                Vector3D clickedPos = null;

                // Find if the mouse clicked on any body
                for (CelestialBody body : clickableBodies) {
                    AbsoluteDate dotDate = targetBranch.contains(body) ? res.arrDate : res.depDate;
                    if (body == currentHCP) dotDate = res.depDate; // Center body doesn't move relative to itself

                    Vector3D pos = getPVRelativeTo(body, currentHCP, dotDate).getPosition();
                    double screenX = cx + (pos.getX() * currentScale);
                    double screenY = cy + (pos.getY() * currentScale);

                    // Pythagorean theorem for distance between mouse and planet center (10 pixel hit radius)
                    if (Math.hypot(event.getX() - screenX, event.getY() - screenY) <= 10.0) {
                        clickedBody = body;
                        clickedPos = pos;
                        break;
                    }
                }

                // If we clicked a planet, snap the camera and zoom in!
                if (clickedBody != null) {
                    // Bump the zoom level significantly (cap it at 5000)
                    zoomLevel = Math.max(zoomLevel * 3.0, 15.0);
                    zoomLevel = Math.min(zoomLevel, 5000.0);

                    // Calculate the NEW scale after zooming
                    double newScale = ((w / 2.2) / maxDist) * zoomLevel;

                    // Set offsets so that the clicked planet's coordinate becomes the new exact center (w/2, h/2)
                    offsetX = -(clickedPos.getX() * newScale);
                    offsetY = -(clickedPos.getY() * newScale);

                    drawFlightPath(res);
                }
            }
        });

        overviewArea = new TextArea("Select a trajectory to view details.");
        overviewArea.setEditable(false);
        overviewArea.setPrefHeight(150);
        rightPanel.getChildren().addAll(canvasControls, canvas, overviewArea);

        // --- Split Screen Layout ---
        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(leftTabs, rightPanel);
        splitPane.setDividerPositions(0.5);

        // --- Listeners ---
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, newSel) -> {
            if (newSel != null) displayTrajectory(newSel);
        });

        calcBtn.setOnAction(e -> {
            if (useOptimizerBox.isSelected()) {
                overviewArea.setText("Initializing Genetic Algorithm and evolving timelines. Please wait...\n");

                // UI LOCK: Disable button and show progress bar
                calcBtn.setDisable(true);
                progressBar.setProgress(0.0);
                progressBar.setVisible(true);

                currentOrigin = bodiesMap.get(originCombo.getValue());
                currentTarget = bodiesMap.get(targetCombo.getValue());
                currentHCP = getCommonParent(currentOrigin, currentTarget);

                // Run on a background thread so the UI doesn't freeze
                new Thread(() -> {
                    int assists = Integer.parseInt(maxAssistsField.getText());
                    int startYear = Integer.parseInt(startYearField.getText());
                    int startDay = Integer.parseInt(startDayField.getText());
                    int window = Integer.parseInt(durationField.getText());
                    double maxDvLimit = Double.parseDouble(maxDVField.getText());
                    int startDayOffset = ((startYear - 1) * 426) + (startDay - 1);

                    // --- LAYER 1: Graph Search ---
                    List<List<CelestialBody>> sequences = findAssistSequences(currentOrigin, currentTarget, assists);

                    javafx.application.Platform.runLater(() -> {
                        masterData.clear();
                        porkchopChart.getData().clear();
                    });

                    // --- LAYER 2 & 3: Evolve timelines ---
                    int totalRoutes = sequences.size();
                    int completedRoutes = 0;

                    for (List<CelestialBody> route : sequences) {
                        List<String> names = new ArrayList<>();
                        for (CelestialBody b : route) names.add(b.name);
                        String routeName = String.join(" -> ", names);

                        // Grab the Max TOF value from the UI
                        double uiMaxTof = Double.parseDouble(maxTofField.getText());

                        // Pass uiMaxTof as the final parameter
                        GeneticOptimizer ga = new GeneticOptimizer(route, Double.parseDouble(startAltField.getText()),
                                Double.parseDouble(endAltField.getText()), startDayOffset, window, uiMaxTof);

                        double[] bestResult = ga.run();
                        double estimatedDv = bestResult[bestResult.length - 1];

                        if (estimatedDv >= Double.MAX_VALUE - 1.0) continue;


                        double depDayTotal = bestResult[0];
                        int dYear = ((int)depDayTotal / 426) + 1;
                        int dDay = ((int)depDayTotal % 426) + 1;

                        int totalTof = 0;
                        for (int i = 1; i < bestResult.length - 1; i++) {
                            totalTof += bestResult[i];
                        }

                        AbsoluteDate gaDepDate = KSP_EPOCH.shiftedBy(depDayTotal * 21600.0);
                        AbsoluteDate gaArrDate = gaDepDate.shiftedBy(totalTof * 21600.0);

                        TrajectoryResult res = new TrajectoryResult(routeName, route, bestResult, (int)depDayTotal, dYear, dDay, totalTof, estimatedDv, gaDepDate, gaArrDate);

                        // --- LAYER 4: Ejection & Capture V-Infinity Analyzers ---
                        try {
                            // 1. Calculate Ejection (First Leg)
                            CelestialBody bodyA = route.get(0);
                            CelestialBody bodyB = route.get(1);
                            CelestialBody firstHCP = getCommonParent(bodyA, bodyB);

                            double tofFirst = bestResult[1] * 21600.0;
                            AbsoluteDate nextDate = gaDepDate.shiftedBy(tofFirst);

                            Vector3D pStart = getPVRelativeTo(bodyA, firstHCP, gaDepDate).getPosition();
                            Vector3D pEnd = getPVRelativeTo(bodyB, firstHCP, nextDate).getPosition();

                            IodLambert lambertFirst = new IodLambert(firstHCP.mu);
                            Orbit transferFirst = lambertFirst.estimate(EME, true, 0, pStart, gaDepDate, pEnd, nextDate);

                            Vector3D vPlanetStart = getPVRelativeTo(bodyA, firstHCP, gaDepDate).getVelocity();
                            Vector3D vTransStart = transferFirst.getPVCoordinates(gaDepDate, EME).getVelocity();

                            VInfResult ejectResult = checkMoonAssist(gaDepDate, vPlanetStart, vTransStart, bodyA);

                            // 2. Calculate Capture (Last Leg)
                            CelestialBody lastBody = route.get(route.size() - 1);
                            CelestialBody prevBody = route.get(route.size() - 2);
                            CelestialBody lastHCP = getCommonParent(prevBody, lastBody);

                            double tofLast = bestResult[bestResult.length - 2] * 21600.0;
                            AbsoluteDate prevDate = gaArrDate.shiftedBy(-tofLast);

                            Vector3D pPrev = getPVRelativeTo(prevBody, lastHCP, prevDate).getPosition();
                            Vector3D pArr = getPVRelativeTo(lastBody, lastHCP, gaArrDate).getPosition();

                            IodLambert lambertLast = new IodLambert(lastHCP.mu);
                            Orbit transferLast = lambertLast.estimate(EME, true, 0, pPrev, prevDate, pArr, gaArrDate);

                            Vector3D vPlanetArr = getPVRelativeTo(lastBody, lastHCP, gaArrDate).getVelocity();
                            Vector3D vTransArr = transferLast.getPVCoordinates(gaArrDate, EME).getVelocity();

                            VInfResult capResult = checkMoonAssist(gaArrDate, vPlanetArr, vTransArr, lastBody);

                            // Apply both savings!
                            res.applyAssists(ejectResult, capResult);

                        } catch (Exception ignored) {}

                        javafx.application.Platform.runLater(() -> masterData.add(res));


                        // NEW: Update the progress bar!
                        completedRoutes++;
                        double progress = (double) completedRoutes / Math.max(1, totalRoutes);
                        javafx.application.Platform.runLater(() -> progressBar.setProgress(progress));
                    }

                    // UI UNLOCK: Re-enable the button and hide the progress bar
                    javafx.application.Platform.runLater(() -> {
                        if (masterData.isEmpty()) {
                            overviewArea.setText("Optimization Complete!\nNo viable routes found under " + maxDvLimit + " m/s dV.");
                        } else {
                            overviewArea.setText("Optimization Complete! Select a route from the table.");
                        }
                        progressBar.setVisible(false);
                        calcBtn.setDisable(false);
                    });
                }).start();

            } else {
                // For standard 1-Way / Round-Trip, we can just show an indeterminate progress bar (spinning animation)
                calcBtn.setDisable(true);
                progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                progressBar.setVisible(true);

                // Wrap the standard scan in a Platform.runLater so the UI has time to show the spinning bar,
                // or just run it directly if it's already fast enough.
                javafx.application.Platform.runLater(() -> {
                    runScan(
                            originCombo.getValue(), targetCombo.getValue(), Double.parseDouble(startAltField.getText()), Double.parseDouble(endAltField.getText()),
                            Integer.parseInt(startYearField.getText()), Integer.parseInt(startDayField.getText()), Integer.parseInt(durationField.getText()),
                            Double.parseDouble(maxDVField.getText()), tripType.getValue().equals("Round-Trip"), Integer.parseInt(stayTimeField.getText())
                    );
                    progressBar.setVisible(false);
                    calcBtn.setDisable(false);
                });
            }
        });

        BorderPane mainLayout = new BorderPane();
        mainLayout.setTop(controls); mainLayout.setCenter(splitPane);
        stage.setScene(new Scene(mainLayout, 1000, 750));
        stage.show();
    }

    private void runScan(String from, String to, double sAlt, double eAlt, int startYear, int startDay, int windowDays,
                         double limit, boolean isRoundTrip, int stayDays) {
        masterData.clear();
        porkchopChart.getData().clear();

        currentOrigin = bodiesMap.get(from); currentTarget = bodiesMap.get(to); currentHCP = getCommonParent(currentOrigin, currentTarget);
        IodLambert lambertSolver = new IodLambert(currentHCP.mu);
        XYChart.Series<Number, Number> series = new XYChart.Series<>();

        final double KSP_DAY = 21600.0; final int KSP_YEAR = 426;
        int startDayOffset = ((startYear - 1) * KSP_YEAR) + (startDay - 1);

        for (int depDay = startDayOffset; depDay <= startDayOffset + windowDays; depDay += 5) {
            AbsoluteDate depDate = KSP_EPOCH.shiftedBy(depDay * KSP_DAY);
            Vector3D p1 = getPVRelativeTo(currentOrigin, currentHCP, depDate).getPosition();
            Vector3D vPlanetStart = getPVRelativeTo(currentOrigin, currentHCP, depDate).getVelocity();

            for (int tofDays = 50; tofDays <= 600; tofDays += 5) {
                AbsoluteDate arrDate = depDate.shiftedBy(tofDays * KSP_DAY);
                Vector3D p2 = getPVRelativeTo(currentTarget, currentHCP, arrDate).getPosition();
                Vector3D vPlanetEnd = getPVRelativeTo(currentTarget, currentHCP, arrDate).getVelocity();

                try {
                    Orbit transferOrbit = lambertSolver.estimate(EME, true, 0, p1, depDate, p2, arrDate);
                    double dv1 = calculateBurn(transferOrbit.getPVCoordinates(depDate, EME).getVelocity(), vPlanetStart, currentOrigin, sAlt);
                    double dv2 = calculateBurn(transferOrbit.getPVCoordinates(arrDate, EME).getVelocity(), vPlanetEnd, currentTarget, eAlt);

                    if (!isRoundTrip) {
                        if (dv1 + dv2 < limit) {
                            int dYear = (depDay / KSP_YEAR) + 1; int dDay = (depDay % KSP_YEAR) + 1;
                            masterData.add(new TrajectoryResult(depDay, dYear, dDay, tofDays, dv1, dv2, transferOrbit, depDate, arrDate));
                        }
                    } else {
                        // --- RETURN TRIP CALCULATION ---
                        AbsoluteDate returnDepDate = arrDate.shiftedBy(stayDays * KSP_DAY);
                        Vector3D p3 = getPVRelativeTo(currentTarget, currentHCP, returnDepDate).getPosition();
                        Vector3D vPlanet3 = getPVRelativeTo(currentTarget, currentHCP, returnDepDate).getVelocity();

                        // We step by 10 days on the return to save CPU time
                        for (int retTof = 50; retTof <= 600; retTof += 10) {
                            AbsoluteDate returnArrDate = returnDepDate.shiftedBy(retTof * KSP_DAY);
                            Vector3D p4 = getPVRelativeTo(currentOrigin, currentHCP, returnArrDate).getPosition();
                            Vector3D vPlanet4 = getPVRelativeTo(currentOrigin, currentHCP, returnArrDate).getVelocity();

                            try {
                                Orbit returnOrbit = lambertSolver.estimate(EME, true, 0, p3, returnDepDate, p4, returnArrDate);
                                double dv3 = calculateBurn(returnOrbit.getPVCoordinates(returnDepDate, EME).getVelocity(), vPlanet3, currentTarget, eAlt);
                                double dv4 = calculateBurn(returnOrbit.getPVCoordinates(returnArrDate, EME).getVelocity(), vPlanet4, currentOrigin, sAlt);

                                if (dv1 + dv2 + dv3 + dv4 < limit) {
                                    int dYear = (depDay / KSP_YEAR) + 1; int dDay = (depDay % KSP_YEAR) + 1;
                                    masterData.add(new TrajectoryResult(depDay, dYear, dDay, tofDays, dv1, dv2, transferOrbit, depDate, arrDate,
                                            stayDays, retTof, dv3, dv4, returnOrbit, returnDepDate, returnArrDate));
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // Color Mapping & Click Listeners
        if (!masterData.isEmpty()) {
            double minDV = masterData.stream().mapToDouble(TrajectoryResult::getTotalDv).min().orElse(0);
            for (TrajectoryResult res : masterData) {
                XYChart.Data<Number, Number> dataPoint = new XYChart.Data<>(res.getDepDayTotal(), res.getTofDays());
                double ratio = Math.max(0, Math.min(1, (res.getTotalDv() - minDV) / (limit - minDV)));

                javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(4);
                dot.setFill(Color.hsb(240 - (ratio * 240), 1.0, 1.0));

                // FEATURE: Clickable Plot Points
                dot.setOnMouseClicked(e -> {
                    table.getSelectionModel().select(res); // Sync table selection
                    displayTrajectory(res);
                });

                dataPoint.setNode(dot);
                series.getData().add(dataPoint);
            }
            porkchopChart.getData().add(series);
        }
    }


    private Orbit getBodyOrbit(CelestialBody b, AbsoluteDate date, double sunMu, Frame frame) {
        // Construct a perfectly repeating Keplerian Orbit based on your JSON data
        return new KeplerianOrbit(
                b.sma, b.eccentricity, Math.toRadians(b.inclination),
                0, 0, 0, PositionAngleType.MEAN,
                frame, date, sunMu
        );
    }

    private Vector3D getBodyPosition(CelestialBody b, AbsoluteDate date, double sunMu) {
        if (b.name.equals("Sun")) return Vector3D.ZERO;

        // Construct a Keplerian Orbit based on JSON data
        KeplerianOrbit orbit = new KeplerianOrbit(
                b.sma, b.eccentricity, Math.toRadians(b.inclination),
                0, 0, 0, PositionAngleType.MEAN,
                DataContext.getDefault().getFrames().getICRF(), // Use Inertial Frame
                date, sunMu
        );
        return orbit.getPVCoordinates().getPosition();
    }

    private double estimateDV(Vector3D p1, Vector3D p2, int days) {
        // Simplified Hohmann-style check for placeholder
        // Replace with actual Lambert solver for final version
        return 1500 + (Math.random() * 2000);
    }

    public static void main(String[] args) {
        launch(args);
    }
}