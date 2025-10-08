package AIT_2022.module.algorithm;

import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.agent.communication.MessageManager;
import rescuecore2.worldmodel.*;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import java.util.*;
import java.util.stream.*;

// visual debug {{{
//import java.io.Serializable;
//import com.mrl.debugger.remote.VDClient;
// }}}

public class DispersibleGrouping extends DynamicClustering {

  private int groupNum = 0;
  private List<List<EntityID>> groups = new ArrayList<List<EntityID>>();

  // visual debug {{{
  //private final VDClient vdclient = VDClient.getInstance();
  // }}}

  public DispersibleGrouping(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
    // visual debug {{{
    //this.vdclient.init("localhost", 1099);
    // }}}
  }

  // just ovverrides {{{
  @Override
  public Clustering precompute(PrecomputeData pd) {
    super.precompute(pd);
    return this;
  }

  @Override
  public Clustering resume(PrecomputeData pd) {
    super.resume(pd);
    return this;
  }

  @Override
  public Clustering preparate() {
    super.preparate();
    return this;
  }
// }}}

  @Override
  public Clustering updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }
    return this;
  }

  @Override
  public int getClusterNumber() {
    return this.groupNum;
  }

  @Override
  public int getClusterIndex(StandardEntity entity) {
    return this.getClusterIndex(entity.getID());
  }

  @Override
  public int getClusterIndex(EntityID id) {
    int ret = -1;
    for (List<EntityID> g : this.groups) {
      if (g.contains(id)) {
        ret = this.groups.indexOf(g);
      }
    }
    return ret;
  }

  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    if (0 > i && i >= this.groupNum) {
      return null;
    }
    return this.groups.get(i);
  }

  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {
    if (0 > i && i >= this.groupNum) {
      return null;
    }
    Stream<StandardEntity> ret = this.groups.get(i)
        .stream().map(this.worldInfo::getEntity);
    return ret.collect(Collectors.toList());
  }

  @Override
  public Clustering calc() {
    // collect agents & buildings
    final StandardEntityURN myUrn = this.agentInfo.me().getStandardURN();
    List<EntityID> agents = this.worldInfo.getEntityIDsOfType(myUrn)
        .stream().sorted(Comparator.comparing(EntityID::getValue))
        .collect(Collectors.toList());
    List<EntityID> buildings = this.worldInfo.getEntityIDsOfType(BUILDING)
        .stream().sorted(Comparator.comparing(EntityID::getValue))
        .collect(Collectors.toList());

    // grouping them into dispersible groups
    this.groups.clear();
    this.groupNum = this.computeGroupNum(agents);
    for (int i = 0; i < this.groupNum; i++) {
      this.groups.add(i, new ArrayList<EntityID>());
    }
    int cntr = 0;
    while (!(agents.isEmpty() && buildings.isEmpty())) {
      cntr = cntr % this.groupNum;
      if (!agents.isEmpty()) {
        this.groups.get(cntr).add(agents.remove(0));
      }
      if (!buildings.isEmpty()) {
        this.groups.get(cntr).add(buildings.remove(0));
      }
      cntr++;
    }
    // visual debug {{{
    //final int idx = this.getClusterIndex(this.agentInfo.getID());
    //final List<Integer> data = this.groups.get(idx).stream()
    //    .map(this.worldInfo::getEntity)
    //    .filter(Building.class::isInstance)
    //    .map(StandardEntity::getID)
    //    .mapToInt(EntityID::getValue).boxed()
    //    .collect(Collectors.toList());
    //this.vdclient.drawAsync(
    //    this.agentInfo.getID().getValue(),
    //    "ClusterArea",
    //    (Serializable) data);
    // }}}
    return this;
  }

  private final static double COEF_NUMBER = 2f;

  private <T> int computeGroupNum(Collection<T> agents) {
    double size = agents.size();
    return (int) Math.ceil(Math.sqrt(size) / COEF_NUMBER);
  }
}
