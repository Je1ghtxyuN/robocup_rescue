package AIT_2022.module.complex.fb;

import java.util.*;
import java.util.function.Predicate;

import static java.util.Comparator.*;

import java.util.stream.Collectors;

import adf.core.agent.action.Action;
import adf.core.agent.action.fire.ActionExtinguish;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.*;
import adf.core.component.module.complex.Search;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;
import static rescuecore2.standard.entities.StandardEntityConstants.Fieryness.*;

import rescuecore2.worldmodel.EntityID;

// visual debug {{{
//import java.awt.Shape;
//import java.io.Serializable;
//import com.mrl.debugger.remote.VDClient;
// }}}

public class AITBuildingSearch extends Search {

  private PathPlanning pathPlanner;
  private Clustering clusterer;
  private Clustering stuckedHumans;
  private Clustering failedMove;

  private EntityID target;

  private final EntityID myId;
  private Random random = new Random();

  private int number;
  private int currIndex;
  private List<EntityID> currents = new ArrayList<>();
  private Map<Integer, Collection<EntityID>> interrupts = new HashMap<>();
  private Map<Integer, EntityID> ignoreMap = new HashMap<>();
  private Map<EntityID, Integer> temperatureMap = new HashMap<>();

  // visual debug {{{
  //private VDClient vdclient = VDClient.getInstance();
  // }}}

  public AITBuildingSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    this.pathPlanner = mm.getModule("AIT.FB.BuildingSearch.PathPlanning");
    this.clusterer = mm.getModule("AIT.FB.BuildingSearch.Clustering");
    this.stuckedHumans = mm.getModule("AIT.FB.BuildingSearch.StuckHumans");
    this.failedMove = mm.getModule("AIT.FB.BuildingSearch.FailedMove");

    this.registerModule(this.pathPlanner);
    this.registerModule(this.clusterer);
    this.registerModule(this.stuckedHumans);
    this.registerModule(this.failedMove);

    this.myId = this.agentInfo.getID();
    this.random.setSeed(ai.getID().getValue());

    // visual debug {{{
    //this.vdclient.init("localhost", 1099);
    // }}}
  }

  @Override
  public Search preparate() {
    super.preparate();
    if (this.getCountPreparate() > 1) {
      return this;
    }
    this.clusterer.calc();
    this.number = this.clusterer.getClusterNumber();
    this.currIndex = this.clusterer.getClusterIndex(this.myId);
    return this;
  }

  @Override
  public Search resume(PrecomputeData pd) {
    super.resume(pd);
    if (this.getCountResume() > 1) {
      return this;
    }
    this.clusterer.calc();
    this.number = this.clusterer.getClusterNumber();
    this.currIndex = this.clusterer.getClusterIndex(this.myId);
    return this;
  }

  @Override
  public EntityID getTarget() {
    return this.target;
  }


  private int N_STEPS_SEARCH_FOR_TEMP = 30; // not static final

  @Override
  public Search updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }
    if (this.stuckedHumans.getClusterIndex(this.myId) >= 0) {
      return this;
    }

    // if any, interrupt buildings around extinguished building as a cluster
    final int t = this.agentInfo.getTime();
    final Action a = this.agentInfo.getExecutedAction(t - 1);
    if (a instanceof ActionExtinguish) {
      ActionExtinguish ae = (ActionExtinguish) a;
      this.interruptClusterFireInside(ae);
    }

    // if any, interrupt buildings around buildings that have higher temperature
    // as a cluster in N step
    if (t <= N_STEPS_SEARCH_FOR_TEMP) {
      final Collection<EntityID> higherTemps = this.findHigherTemps();
      if (!higherTemps.isEmpty()) {
        this.interruptClusterHigherTempInside(higherTemps);
      }
    }

    // update the cluster me focusing
    this.updateCluster();
    int count = 0;
    while (this.currents.isEmpty()) {
      if (count > this.number) {
        break;
      }
      this.currents = new ArrayList<>(this.nextCluster());
      count++;
    }

    // visual debug {{{
    //if (this.myUrn.equals(FIRE_BRIGADE))
    //{
    //List<Shape> datas = new ArrayList<>();
    //for (EntityID id : this.currents)
    //{
    //    Area area = (Area) this.worldInfo.getEntity(id);
    //    datas.add(area.getShape());
    //}
    //this.vdclient.drawAsync(
    //    this.agentInfo.getID().getValue(),
    //    "SamplePolygon",
    //    (Serializable) datas);
    //}
    // }}}

    this.sendSelectedInfo(mm);
    return this;
  }

  private static final int INTERVAL_INTERRUPT_FIRE = 20;

  private void interruptClusterFireInside(ActionExtinguish action) {
    final EntityID target = action.getTarget();
    Collection<EntityID> around = this.findAroundThere(target);
    this.currents.addAll(this.extractCandities(around));
    this.currents = this.currents
        .stream().distinct().collect(Collectors.toList());
    final int t = this.agentInfo.getTime();
    this.addIntoMap(this.interrupts, t + INTERVAL_INTERRUPT_FIRE, around);
  }

  private static final int THOLD_TEMP = 15;

  private Collection<EntityID> findHigherTemps() {
    if (this.worldInfo.getChanged().getChangedEntities().isEmpty()) {
      return Collections.emptySet();
    }

    Set<EntityID> ret = this.worldInfo.getChanged().getChangedEntities()
        .stream()
        .map(this.worldInfo::getEntity)
        .filter(Building.class::isInstance)
        .filter(Predicate.not(Refuge.class::isInstance))
        .filter(Predicate.not(AmbulanceCentre.class::isInstance))
        .filter(Predicate.not(FireStation.class::isInstance))
        .filter(Predicate.not(PoliceOffice.class::isInstance))
        .map(Building.class::cast)
        .filter(Building::isFierynessDefined)
        .filter(bld -> bld.getFieryness() == 0)
        .filter(Building::isTemperatureDefined)
        .filter(bld -> bld.getTemperature() >= THOLD_TEMP)
        .map(Building::getID)
        .collect(Collectors.toSet());

    return ret;
  }

  private static final int INTERVAL_INTERRUPT_TEMP = 10;

  private void interruptClusterHigherTempInside(Collection<EntityID> candidates) {
    final EntityID target = candidates.stream().max(
            Comparator.comparingInt(id ->
                ((Building) this.worldInfo.getEntity(id)).getTemperature()))
        .get();
    Collection<EntityID> around = this.findAroundThere(target);
    this.currents.addAll(this.extractCandities(around));
    this.currents = this.currents
        .stream().distinct().collect(Collectors.toList());
    final int t = this.agentInfo.getTime();
    this.addIntoMap(this.interrupts, t + INTERVAL_INTERRUPT_TEMP, around);
  }

  private static final int ADJACENT_RANGE = 55_000;

  private Collection<EntityID> findAroundThere(EntityID target) {
    Set<EntityID> ret = new HashSet<>(
        this.worldInfo.getObjectIDsInRange(target, ADJACENT_RANGE));
    return ret;
  }

  private static final int IGNORE_TIME = 20;

  private void updateCluster() {
    Collection<EntityID> seens = this.getSeensInSight();
    this.currents.removeAll(seens);
    this.ignoreMap.put(this.agentInfo.getTime() + IGNORE_TIME, this.agentInfo.getPosition());
    this.updateTemperatureMap();
    if (this.cannotReach()) {
      this.currents.remove(this.target);
    }
    if (!this.currents.contains(this.target)) {
      this.target = null;
    }

    final int t = this.agentInfo.getTime();
    if (this.interrupts.containsKey(t)) {
      this.currents.removeAll(this.interrupts.get(t));
    }
  }

  private Collection<EntityID> getSeensInSight() {
    Collection<EntityID> changed =
        this.worldInfo.getChanged().getChangedEntities();
    return this.extractCandities(changed);
  }

  //private int times = 0;
  private Collection<EntityID> nextCluster() {
    this.currIndex = this.random.nextInt(this.number);
    //this.currIndex = (this.currIndex+1) % this.number;
    //this.currIndex = this.currIndex + this.number*this.times++;
    Collection<EntityID> ret =
        this.clusterer.getClusterEntityIDs(this.currIndex);
    return this.extractCandities(ret);
  }

  private void updateTemperatureMap() {
    Set<Building> haveTemperature = this.getSeensInSight().stream()
        .map(this.worldInfo::getEntity)
        .map(Building.class::cast)
        .filter(Building::isTemperatureDefined)
        .filter(bld -> bld.getTemperature() > 0)
        .collect(Collectors.toSet());
    for (Building bld : haveTemperature) {
      this.temperatureMap.put(bld.getID(), bld.getTemperature());
    }

    this.temperatureMap.remove(this.agentInfo.getPosition());
    Set<EntityID> notHaveTemperature = this.getSeensInSight().stream()
        .map(this.worldInfo::getEntity)
        .map(Building.class::cast)
        .filter(Building::isTemperatureDefined)
        .filter(bld -> bld.getTemperature() == 0)
        .map(Building::getID)
        .collect(Collectors.toSet());
    for (EntityID id : notHaveTemperature) {
      this.temperatureMap.remove(id);
    }
  }

  private Collection<EntityID> extractCandities(Collection<EntityID> cluster) {
    Set<EntityID> ret = cluster.stream()
        .map(this.worldInfo::getEntity)
        .filter(Building.class::isInstance)
        .filter(Predicate.not(Refuge.class::isInstance))
        .filter(Predicate.not(AmbulanceCentre.class::isInstance))
        .filter(Predicate.not(FireStation.class::isInstance))
        .filter(Predicate.not(PoliceOffice.class::isInstance))
        .map(Building.class::cast)
        .filter(Predicate.not(b -> b.getFierynessEnum() == BURNT_OUT))
        .map(Building::getID)
        .collect(Collectors.toSet());
    return ret;
  }

  private static final double PROBABILITY = 0.6;

  @Override
  public Search calc() {
    if (this.target != null) {
      return this;
    }
    if (this.stuckedHumans.getClusterIndex(this.myId) >= 0) {
      return this;
    }

    double dice = this.random.nextDouble();

    //temperature search
    Set<EntityID> ignore = this.ignoreMap.keySet().stream()
        .filter(t -> t > this.agentInfo.getTime())
        .map(this.ignoreMap::get)
        .collect(Collectors.toSet());

    List<Map.Entry<EntityID, Integer>> temperatureEntry = new ArrayList<>();
    for (Map.Entry<EntityID, Integer> entry : this.temperatureMap.entrySet()) {
      if (!(ignore.contains(entry.getKey()))) {
        temperatureEntry.add(entry);
      }
    }

    if (temperatureEntry.size() == 1) {
      this.target = temperatureEntry.get(0).getKey();
      return this;
    }

    if (!(temperatureEntry.isEmpty())) {
      Point2D clusterCenter = this.getClusterCenter();
      temperatureEntry.sort(new temperatureValueComparator().reversed()
          .thenComparing(entry -> this.getDistance(clusterCenter,
              new Point2D(this.worldInfo.getLocation(entry.getKey()).first(),
                  this.worldInfo.getLocation(entry.getKey()).second())
          ))
      );
      double sumTemperature = 0.0D;
      for (Integer t : temperatureMap.values()) {
        sumTemperature += (double) t;
      }

      double sumProbability = 0.0D;
      for (Map.Entry<EntityID, Integer> entry : temperatureEntry) {
        sumProbability += (double) entry.getValue() / sumTemperature;
        if (sumProbability >= dice) {
          this.target = entry.getKey();
          return this;
        }
      }
    }

    if (this.currents.size() < 1) {
      this.target = this.worldInfo.getEntityIDsOfType(BUILDING)
          .stream().findAny().get();
      return this;
    }

    List<EntityID> intersection = this.interrupts.keySet()
        .stream().filter(t -> t > this.agentInfo.getTime())
        .map(this.interrupts::get).flatMap(Collection::stream)
        .distinct()
        .filter(id -> this.currents.contains(id))
        .collect(Collectors.toList());

    if (!intersection.isEmpty() && dice < PROBABILITY) {
      this.out("SEARCH CANDs D: " + intersection);
      //this.out("DICE SUCCESS!");
      int i = this.random.nextInt(intersection.size());
      this.target = intersection.get(i);
      this.out("SEARCH #" + this.target);
      return this;
    }

    this.out("SEARCH CANDs: " + this.currents);
    int i = this.random.nextInt(this.currents.size());
    this.target = this.currents.get(i);
    this.out("SEARCH #" + this.target);
    return this;
  }

  private Point2D getClusterCenter() {
    int clusterIndex = this.clusterer.getClusterIndex(this.myId);
    Collection<EntityID> cluster = this.clusterer.getClusterEntityIDs(clusterIndex);
    double clusterSize = cluster.size();
    double sumX = 0.0D;
    double sumY = 0.0D;
    for (EntityID id : cluster) {
      Pair<Integer, Integer> location = this.worldInfo.getLocation(id);
      sumX += (double) location.first();
      sumY += (double) location.second();
    }

    return new Point2D(sumX / clusterSize, sumY / clusterSize);
  }

  private double getDistance(Point2D point1, Point2D point2) {
    return Math.hypot(point2.getX() - point1.getX(), point2.getY() - point1.getY());
  }

  private boolean cannotReach() {
    final EntityID me = this.agentInfo.getID();
    final StandardEntityURN urn = this.agentInfo.me().getStandardURN();

    if (this.target == null) {
      return false;
    }
    if (urn == POLICE_FORCE) {
      return false;
    }

    this.failedMove.calc();
    this.stuckedHumans.calc();

    final boolean failed = this.failedMove.getClusterIndex(me) >= 0;
    final boolean stucked = this.stuckedHumans.getClusterIndex(me) >= 0;
    return stucked || failed;
  }

  private void sendSelectedInfo(MessageManager mm) {
    if (!this.shouldSend()) {
      return;
    }

    Collection<EntityID> seens = this.getSeensInSight();
    final EntityID selected = this.selectOneToSend(seens);
    if (selected == null) {
      return;
    }

    Building bld = (Building) this.worldInfo.getEntity(selected);
    mm.addMessage(new MessageBuilding(true, bld));
    //this.out("SEND_INFO #" + selected);
  }

  private Boolean shouldSend() {
    if (this.agentInfo.getTime() < 1) {
      return false;
    }
    Collection<EntityID> agents = this.worldInfo.getEntityIDsOfType(
        AMBULANCE_TEAM, FIRE_BRIGADE, POLICE_FORCE);
    final StandardEntity here = this.worldInfo.getPosition(this.myId);
    final EntityID maxIdHere = agents.stream()
        .filter(id -> this.worldInfo.getPosition(id).equals(here))
        .sorted(Comparator.comparing(EntityID::getValue).reversed())
        .findFirst().get();
    return myId.equals(maxIdHere);
  }

  private EntityID selectOneToSend(Collection<EntityID> entities) {
    Optional<EntityID> ret = this.extractCandities(entities).stream()
        .map(this.worldInfo::getEntity)
        .filter(Building.class::isInstance)
        .map(Building.class::cast)
        .filter(Building::isTemperatureDefined)
        .sorted(comparing(Building::getTemperature).reversed())
        .map(Building::getID).findFirst();
    return ret.orElse(null);
  }

  private <T, E> void addIntoMap(
      Map<T, Collection<E>> map, T key, Collection<E> col) {
    Collection<E> value = new ArrayList<E>(col);
    if (map.containsKey(key)) {
      this.out("ALREADY EXISTS in 'interrupts'");
      value.addAll(map.get(key));
    }
    map.put(key, value);
  }

  private void out(String str) {
    String ret;
    ret = "🚒  [" + String.format("%10d", this.agentInfo.getID().getValue()) + "]";
    ret += " BUILDING-SEARCH ";
    ret += "@" + String.format("%3d", this.agentInfo.getTime());
    ret += " -> ";
    System.out.println(ret + str);
  }

  private static class temperatureValueComparator implements
      Comparator<Map.Entry<EntityID, Integer>> {

    public int compare(Map.Entry<EntityID, Integer> entry1, Map.Entry<EntityID, Integer> entry2) {
      Integer temperature1 = entry1.getValue();
      Integer temperature2 = entry2.getValue();
      return temperature1.compareTo(temperature2);
    }
  }
}
