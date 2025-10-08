package AIT_2022.module.complex.common;

import java.util.Set;
import java.util.stream.Collectors;

import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import static java.util.Comparator.*;

import java.util.HashSet;

/**
 * 知覚情報をいい感じに取得できるやつ
 */
public class EntityFilter {

  private WorldInfo wi;
  private AgentInfo ai;
  private ScenarioInfo si;
  private Set<EntityID> changed = new HashSet<>();
  private double x = 0.0;
  private double y = 0.0;

  public EntityFilter(WorldInfo wi, AgentInfo ai, ScenarioInfo si) {
    this.wi = wi;
    this.ai = ai;
    this.si = si;
    this.x = ai.getX();
    this.y = ai.getY();
    this.changed = this.wi.getChanged().getChangedEntities();
  }

  public EntityFilter(WorldInfo wi, AgentInfo ai) {
    this.wi = wi;
    this.ai = ai;
    this.x = ai.getX();
    this.y = ai.getY();
    this.changed = this.wi.getChanged().getChangedEntities();
  }

  public void update(WorldInfo wi, AgentInfo ai) {
    this.wi = wi;
    this.ai = ai;
    this.x = ai.getX();
    this.y = ai.getY();
    this.changed = this.wi.getChanged().getChangedEntities();
  }

  public Set<EntityID> getEntityIDs(StandardEntityURN entityType) {
    switch (entityType) {
      case CIVILIAN:
        return this.getCivilianIDs();
      case AMBULANCE_TEAM:
        return this.getAmbulanceTeamIDs();
      case FIRE_BRIGADE:
        return this.getFireBrigadeIDs();
      case POLICE_FORCE:
        return this.getFireBrigadeIDs();
      case BLOCKADE:
        return this.getBlockadeIDs();
      case ROAD:
        return this.getRoadIDs();
      case BUILDING:
        return this.getBuildingIDs();
      case REFUGE:
        return this.getRefugeIDs();
      default:
        return new HashSet<>();
    }
  }

  // 扱い難（要キャスト）
  public Set<?> getEntities(StandardEntityURN entityType) {
    switch (entityType) {
      case CIVILIAN:
        return this.getCivilians();
      case AMBULANCE_TEAM:
        return this.getAmbulanceTeams();
      case FIRE_BRIGADE:
        return this.getFireBrigades();
      case POLICE_FORCE:
        return this.getFireBrigades();
      case BLOCKADE:
        return this.getBlockades();
      case ROAD:
        // TODO
      case BUILDING:
        // TODO
      case REFUGE:
        // TODO
      default:
        return new HashSet<>();
    }
  }

  public EntityID getNearyAT() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof AmbulanceTeam)
        .map(AmbulanceTeam.class::cast)
        .min(comparing(a -> this.distance(a.getID(), this.ai.getID())))
        .map(AmbulanceTeam::getID).orElse(null);
  }

  public Set<AmbulanceTeam> getAmbulanceTeams() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof AmbulanceTeam)
        .map(AmbulanceTeam.class::cast)
        .collect(Collectors.toSet());
  }

  public Set<EntityID> getAmbulanceTeamIDs() {
    return this.getAmbulanceTeams().stream()
        .map(AmbulanceTeam::getID)
        .collect(Collectors.toSet());
  }

  public EntityID getNearyFB() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof FireBrigade)
        .map(FireBrigade.class::cast)
        .min(comparing(a -> this.distance(a.getID(), this.ai.getID())))
        .map(FireBrigade::getID).orElse(null);
  }

  public Set<FireBrigade> getFireBrigades() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof FireBrigade)
        .map(FireBrigade.class::cast)
        .collect(Collectors.toSet());
  }

  public Set<EntityID> getFireBrigadeIDs() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof FireBrigade)
        .map(FireBrigade.class::cast)
        .map(FireBrigade::getID)
        .collect(Collectors.toSet());
  }

  public EntityID getNearyPF() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof PoliceForce)
        .map(PoliceForce.class::cast)
        .min(comparing(a -> this.distance(a.getID(), this.ai.getID())))
        .map(PoliceForce::getID).orElse(null);
  }

  public Set<PoliceForce> getPoliceForces() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof PoliceForce)
        .map(PoliceForce.class::cast)
        .collect(Collectors.toSet());
  }

  public Set<EntityID> getPoliceForceIDs() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof PoliceForce)
        .map(PoliceForce.class::cast)
        .map(PoliceForce::getID)
        .collect(Collectors.toSet());
  }

  public EntityID getNearyCivilianID() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof Civilian)
        .map(Civilian.class::cast)
        .min(comparing(a -> this.distance(a.getID(), this.ai.getID())))
        .map(Civilian::getID).orElse(null);
  }

  public Set<Civilian> getCivilians() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof Civilian)
        .map(Civilian.class::cast)
        .collect(Collectors.toSet());
  }

  public Set<EntityID> getCivilianIDs() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof Civilian)
        .map(Civilian.class::cast)
        .map(Civilian::getID)
        .collect(Collectors.toSet());
  }

  public EntityID getNearyATinVoiceRange() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof AmbulanceTeam)
        .filter(e -> this.wi.getPosition(e.getID()) instanceof Road).map(AmbulanceTeam.class::cast)
        .min(comparing(a -> this.wi.getDistance(a.getPosition(), this.ai.getPosition())))
        .filter(a -> this.wi.getDistance(a.getPosition(), this.ai.getID()) < this.si
            .getRawConfig().getIntValue("comms.channels.0.range"))
        .map(AmbulanceTeam::getID).orElse(null);
  }

  public EntityID getNearyFBinVoiceRange() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof FireBrigade)
        .filter(e -> this.wi.getPosition(e.getID()) instanceof Road).map(FireBrigade.class::cast)
        .min(comparing(a -> this.wi.getDistance(a.getPosition(), this.ai.getPosition())))
        .filter(a -> this.wi.getDistance(a.getPosition(), this.ai.getID()) < this.si
            .getRawConfig().getIntValue("comms.channels.0.range"))
        .map(FireBrigade::getID).orElse(null);
  }

  public EntityID getNearyPFinVoiceRange() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof PoliceForce)
        .filter(e -> this.wi.getPosition(e.getID()) instanceof Road).map(PoliceForce.class::cast)
        .min(comparing(a -> this.wi.getDistance(a.getPosition(), this.ai.getPosition())))
        .filter(a -> this.wi.getDistance(a.getPosition(), this.ai.getID()) < this.si
            .getRawConfig().getIntValue("comms.channels.0.range"))
        .map(PoliceForce::getID).orElse(null);
  }

  public Set<Blockade> getBlockades() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof Blockade)
        .map(Blockade.class::cast)
        .collect(Collectors.toSet());
  }

  public Set<EntityID> getBlockadeIDs() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof Blockade)
        .map(Blockade.class::cast)
        .map(Blockade::getID)
        .collect(Collectors.toSet());
  }

  public Blockade getNearyBlockade() {
    return this.nearyBlockade(this.getBlockades(), this.x, this.y);
  }

  public Set<Road> getRoads() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof Road)
        .map(Road.class::cast)
        .collect(Collectors.toSet());
  }

  public Set<EntityID> getRoadIDs() {
    return this.getRoadIDs().stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof Road)
        .map(Road.class::cast)
        .map(Road::getID)
        .collect(Collectors.toSet());
  }

  public Set<Building> getBuildings() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof Building)
        .map(Building.class::cast)
        .collect(Collectors.toSet());
  }

  public Set<EntityID> getBuildingIDs() {
    return this.getBuildingIDs().stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof Building)
        .map(Building.class::cast)
        .map(Building::getID)
        .collect(Collectors.toSet());
  }

  public Set<Refuge> getRefuges() {
    return this.changed.stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof Refuge)
        .map(Refuge.class::cast)
        .collect(Collectors.toSet());
  }

  public Set<EntityID> getRefugeIDs() {
    return this.getRefugeIDs().stream()
        .map(this.wi::getEntity)
        .filter(e -> e instanceof Refuge)
        .map(Refuge.class::cast)
        .map(Refuge::getID)
        .collect(Collectors.toSet());
  }

  private double distance(EntityID a1, EntityID a2) {
    Pair<Integer, Integer> p1 = this.wi.getLocation(a1);
    Pair<Integer, Integer> p2 = this.wi.getLocation(a2);
    return Math.hypot(
        Math.abs(p1.first() - p2.first()), Math.abs(p1.second() - p2.second()));
  }

  private Blockade nearyBlockade(Set<Blockade> bs, double x, double y) {
    if (bs == null) {
      return null;
    }
    Blockade ret = null;
    double dis = Double.MAX_VALUE;
    final Stucked stucked = new Stucked(bs, x, y);
    for (Blockade b : bs) {
      if (stucked.isStucked()) {
        return b;
      }
      final double d = this.getDistance(b, x, y);
      if (dis > d) {
        ret = b;
        dis = d;
      }
    }
    return ret;
  }

  private double getDistance(Blockade b, double x, double y) {
    int[] apexs = b.getApexes();
    double dis = Double.MAX_VALUE;
    for (int i = 0; i < apexs.length / 4; ++i) {
      final Line2D line = new Line2D(
          new Point2D(apexs[i], apexs[i + 1]),
          new Point2D(apexs[i + 2], apexs[i + 3]));
      final Point2D p = new Point2D(x, y);
      final double d = this.calcDistancePointToLine(p, line);
      if (dis > d) {
        dis = d;
      }
    }
    return dis;
  }

  private double calcDistancePointToLine(Point2D point, Line2D line) {
    final Point2D l1 = line.getOrigin();
    final Point2D l2 = line.getEndPoint();
    return Math
        .abs((l2.getX() - l1.getX()) * (l1.getY() - point.getY())
            - (l1.getX() - point.getX()) * (l2.getY() - l1.getY()))
        / Math.sqrt(Math.pow(l2.getX() - l1.getX(), 2) + Math.pow(l2.getY() - l1.getY(), 2));
  }

}
