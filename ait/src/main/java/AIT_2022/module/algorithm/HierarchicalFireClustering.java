package AIT_2022.module.algorithm;

import java.util.*;
import java.util.stream.Collectors;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.*;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.worldmodel.EntityID;

import java.awt.Polygon;
import java.awt.Rectangle;
// visual debug {{{
//import java.io.Serializable;
//import com.mrl.debugger.remote.VDClient;
// }}}

public class HierarchicalFireClustering extends DynamicClustering {

  final Clustering neighborBuildings;
  List<Cluster> clusters = new LinkedList<>();

  // visual debug {{{
  //private final VDClient vdclient = VDClient.getInstance();
  // }}}

  public HierarchicalFireClustering(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
    this.neighborBuildings = mm.getModule(
        "AIT.Algorithm.HierarchicalFireClustering.NeighborBuildings");
    this.registerModule(this.neighborBuildings);
    // visual debug {{{
    //this.vdclient.init("localhost", 1099);
    // }}}
  }

  @Override
  public int getClusterNumber() {
    return this.clusters.size();
  }

  @Override
  public int getClusterIndex(EntityID id) {
    Optional<Integer> ret = this.clusters.stream()
        .filter(c -> c.members().contains(id))
        .map(c -> this.clusters.indexOf(c)).findAny();
    return ret.orElse(-1);
  }

  @Override
  public int getClusterIndex(StandardEntity entity) {
    return this.getClusterIndex(entity.getID());
  }

  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    return this.clusters.get(i).members();
  }

  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {
    List<StandardEntity> ret = this.clusters.get(i).members().stream()
        .map(this.worldInfo::getEntity)
        .collect(Collectors.toList());
    return ret;
  }

  @Override
  public Clustering calc() {
    this.clusters.clear();
    final Collection<EntityID> fireIds = this.collectFireIds();
    if (fireIds.isEmpty()) {
      return this;
    }
    this.clusters = this.clustering(fireIds);

    // visual debug {{{
    //List<Polygon> datas = new ArrayList<>();
    //for (Cluster cluster : this.clusters)
    //{
    //    ConvexHull convexHull = new ConvexHull();
    //    cluster.members.stream().map(this.worldInfo::getEntity)
    //            .filter(Area.class::isInstance).map(Area.class::cast)
    //            .forEach(a -> convexHull.add(a));
    //    convexHull.compute();
    //    datas.add(convexHull.get());
    //}
    //this.vdclient.drawAsync(
    //        this.agentInfo.getID().getValue(),
    //        "ClusterConvexhull",
    //        (Serializable) datas);
    // }}}

    return this;
  }

  private Collection<EntityID> collectFireIds() {
    final Collection<EntityID> bIds = this.worldInfo.getEntityIDsOfType(
        BUILDING, GAS_STATION, REFUGE,
        AMBULANCE_CENTRE, FIRE_STATION, POLICE_OFFICE);
    Collection<EntityID> ret = bIds.stream()
        .map(this.worldInfo::getEntity)
        .map(Building.class::cast)
        .filter(Building::isOnFire)
        .map(Building::getID)
        .collect(Collectors.toList());
    return ret;
  }

  private List<Cluster> clustering(Collection<EntityID> targets) {
    List<Cluster> openlist = new ArrayList<>();
    List<Cluster> ret = new ArrayList<>();
    targets.stream().map(e -> new Cluster(e)).forEach(c -> openlist.add(c));

    while (!openlist.isEmpty()) {
      Cluster picked = openlist.remove(0);
      Cluster closeOne = this.findAnyClose(picked, openlist);
      if (closeOne == null) {
        ret.add(picked);
        continue;
      }
      openlist.remove(closeOne);
      Cluster newOne = picked.merge(closeOne);
      openlist.add(newOne);
    }
    return ret;
  }

  private Cluster findAnyClose(Cluster cluster, Collection<Cluster> others) {
    Optional<Cluster> ret = others.stream()
        .filter(o -> cluster.isClose(o)).findAny();
    return ret.orElse(null);
  }

  // inner class {{{
  class Cluster {

    private List<EntityID> members;

    public Cluster(EntityID member) {
      this.members = new ArrayList<>();
      this.members.add(member);
    }

    public Cluster(Collection<EntityID> members) {
      this.members = new ArrayList<>(members);
    }

    public List<EntityID> members() {
      return this.members;
    }

    public Cluster merge(Cluster other) {
      this.members.addAll(other.members());
      return this;
    }

    public boolean isClose(Cluster other) {
      for (EntityID e1 : this.members) {
        final int i = neighborBuildings.getClusterIndex(e1);
        final Collection<EntityID> neighs =
            neighborBuildings.getClusterEntityIDs(i);
        final int cnt = (int) other.members().stream()
            .filter(e2 -> neighs.contains(e2)).count();
        if (cnt > 0) {
          return true;
        }
      }
      return false;
    }

    @Override
    public String toString() {
      StringJoiner sjmembers = new StringJoiner(",");
      this.members.forEach(id -> sjmembers.add("" + id));
      return "[" + sjmembers + "]";
    }
  }
  // }}}
}
