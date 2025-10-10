package AIT_2022.module.algorithm;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.StaticClustering;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.stream.Collectors;

public class OvercrowdingArea extends StaticClustering {

  private final StandardEntityURN myUrn;
  private static final int ASSUMED_INDEX = 0;
  private static final int ASSUMED_nCluster = 1;
  private final static String CLUSTER_KEY = "AIT_2022.module.algorithm.OvercrowdingArea.c";

  private Collection<EntityID> overcrowdingAreas = new HashSet<>();

  public OvercrowdingArea(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
    this.myUrn = this.agentInfo.me().getStandardURN();
  }

  //Override{{{
  @Override
  public Clustering precompute(PrecomputeData pd) {
    super.precompute(pd);
    if (this.getCountPrecompute() > 1) {
      return this;
    }

    this.calc();

    List<EntityID> ret = new ArrayList<>(this.overcrowdingAreas);
    pd.setEntityIDList(CLUSTER_KEY, ret);

    return this;
  }

  @Override
  public Clustering resume(PrecomputeData pd) {
    super.resume(pd);
    if (this.getCountResume() > 1) {
      return this;
    }

    List<EntityID> ret = pd.getEntityIDList(CLUSTER_KEY);
    this.overcrowdingAreas.addAll(ret);

    return this;
  }

  @Override
  public Clustering preparate() {
    super.preparate();
    if (this.getCountPreparate() > 1) {
      return this;
    }

    this.calc();

    return this;
  }

  @Override
  public int getClusterNumber() {
    return ASSUMED_nCluster;
  }

  @Override
  public int getClusterIndex(StandardEntity standardEntity) {
    return ASSUMED_INDEX;
  }

  @Override
  public int getClusterIndex(EntityID entityID) {
    return ASSUMED_INDEX;
  }

  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {
    return this.overcrowdingAreas
        .stream()
        .map(this.worldInfo::getEntity)
        .collect(Collectors.toSet());
  }

  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    return this.overcrowdingAreas;
  }

  @Override
  public Clustering calc() {
    int range = this.scenarioInfo.getPerceptionLosMaxDistance();

    this.overcrowdingAreas.addAll(seekOvercrowdingArea(StandardEntityURN.FIRE_BRIGADE, range));
    this.overcrowdingAreas.addAll(seekOvercrowdingArea(StandardEntityURN.AMBULANCE_TEAM, range));
    this.overcrowdingAreas.addAll(seekOvercrowdingArea(StandardEntityURN.POLICE_FORCE, range));

    return this;
  }
  //}}}

  /**
   * Find Overcrowding areas of the agent type specified by StandardEntityURN.
   *
   * @param urn   The agents type.
   * @param range Distance at which an agent judges another agent to be close.
   * @return Overcrowding areas.
   */
  private Collection<EntityID> seekOvercrowdingArea(StandardEntityURN urn, int range) {
    Collection<EntityID> ret = new HashSet<>();
    Collection<StandardEntity> agents = this.worldInfo.getEntitiesOfType(urn);

    for (StandardEntity e : agents) {
      EntityID position = ((Human) e).getPosition();
      if (ret.contains(position)) {
        continue;
      }
      if (urn.equals(StandardEntityURN.AMBULANCE_TEAM)) {
        if (isOvercrowdingArea(position, StandardEntityURN.AMBULANCE_TEAM, range)) {
          ret.add(position);
        }
      } else if (urn.equals(StandardEntityURN.FIRE_BRIGADE)) {
        if (isOvercrowdingArea(position, StandardEntityURN.FIRE_BRIGADE, range)) {
          ret.add(position);
        }
      } else if (urn.equals(StandardEntityURN.POLICE_FORCE)) {
        if (isOvercrowdingArea(position, StandardEntityURN.POLICE_FORCE, range)) {
          ret.add(position);
        }
      }
    }
    return ret;
  }

  /**
   * Judge if the area is overcrowded or not.
   *
   * @param position Target area.
   * @param urn Target agent type.
   * @param range Distance at which an agent judges another agent to be close.
   * @return Position is overcrowding area or not.
   */
  private static final int OVERCROWDING_RATIO = 2;

  private boolean isOvercrowdingArea(EntityID position, StandardEntityURN urn, int range) {
    long nAgent = 0L;
    if (urn.equals(StandardEntityURN.AMBULANCE_TEAM)) {
      nAgent = this.scenarioInfo.getScenarioAgentsAt();
    } else if (urn.equals(StandardEntityURN.FIRE_BRIGADE)) {
      nAgent = this.scenarioInfo.getScenarioAgentsFb();
    } else if (urn.equals(StandardEntityURN.POLICE_FORCE)) {
      nAgent = this.scenarioInfo.getScenarioAgentsPf();
    }
    if (nAgent <= 0) {
      return false;
    }

    long nInRange = this.worldInfo.getObjectIDsInRange(position, range)
        .stream()
        .map(this.worldInfo::getEntity)
        .filter(e -> e.getStandardURN().equals(urn))
        .count();

    return (nAgent / OVERCROWDING_RATIO) < nInRange;
  }
}
