package AIT_2022.module.algorithm;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.stream.*;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.*;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

// visual debug {{{
import java.io.Serializable;
import com.mrl.debugger.remote.VDClient;
// }}}

public class MergedClustering extends DynamicClustering {

  private final Clustering clusterer;

  private final StandardEntityURN myUrn;
  private int number = 0;
  private Map<Integer, Cluster> clusters = new HashMap<>();
  private Map<Integer, EntityID> assignees = new HashMap<>();
  private Map<Integer, List<Integer>> adjacency = new HashMap<>();
  private Map<Integer, Cluster> caches = new HashMap<>();
  // visual debug {{{
  private final VDClient vdclient = VDClient.getInstance();
  // }}}

  public MergedClustering(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
    this.clusterer = this.moduleManager.getModule("AIT.Algorithm.MergedClustering.Clustering");
    this.registerModule(this.clusterer);
    this.myUrn = ai.me().getStandardURN();
    // visual debug {{{
    this.vdclient.init("localhost", 1099);
    // }}}
  }

  private void initClusters() {
    List<EntityID> agents = this.worldInfo.getEntityIDsOfType(myUrn)
        .stream().collect(Collectors.toList());

    this.clusterer.calc();
    this.number = this.clusterer.getClusterNumber();
    for (EntityID agent : agents) {
      final int i = this.clusterer.getClusterIndex(agent);
      final List<EntityID> members =
          new ArrayList<>(this.clusterer.getClusterEntityIDs(i));
      Cluster cluster = new Cluster(agent, members);
      this.clusters.put(i, cluster);
      this.assignees.put(i, agent);
    }
  }

  private java.awt.geom.Area clusterToArea(Cluster cluster) {
    ConvexHull convexHull = new ConvexHull();
    cluster.members().stream().map(this.worldInfo::getEntity)
        .filter(Area.class::isInstance).map(Area.class::cast)
        .forEach(a -> convexHull.add(a));
    convexHull.compute();
    return new java.awt.geom.Area(convexHull.get());
  }

  private void scaleArea(java.awt.geom.Area area, double scale) {
    AffineTransform at = new AffineTransform();
    Rectangle rect = area.getBounds();
    double cx = rect.getCenterX();
    double cy = rect.getCenterY();
    at.translate(cx, cy);
    at.scale(scale, scale);
    at.translate(-cx, -cy);
    area.transform(at);
  }

  private final static double COEF_SCALE = 1.2f;

  private void initAdjacency() {
    Map<Cluster, java.awt.geom.Area> clusterArea = new HashMap<>();
    for (Cluster cluster : this.clusters.values()) {
      java.awt.geom.Area area = this.clusterToArea(cluster);
      this.scaleArea(area, COEF_SCALE);
      clusterArea.put(cluster, area);
    }

    // find neighbors of each clusters scaling its shape
    this.adjacency = new HashMap<>();
    final int size = this.number;
    for (int i = 0; i < size; i++) {
      this.adjacency.put(i, new ArrayList<>());
    }

    for (int i = 0; i < size - 1; i++) {
      for (int j = i + 1; j < size; j++) {
        Cluster ci = clusters.get(i);
        Cluster cj = clusters.get(j);
        java.awt.geom.Area ai =
            (java.awt.geom.Area) clusterArea.get(ci).clone();
        java.awt.geom.Area aj =
            (java.awt.geom.Area) clusterArea.get(cj).clone();
        ai.intersect(aj);
        if (ai.isEmpty()) {
          continue;
        }

        this.adjacency.get(i).add(j);
        this.adjacency.get(j).add(i);
      }
    }
  }

  private final static String MODULE_NAME =
      "AIT_2022.module.algorithm.MergedClustering";
  private final static String PD_ADJACENCY = MODULE_NAME + ".ad";

  private String addSuffixToKey(String path) {
    return path + "." + this.myUrn;
  }

  private String addSuffixToKey(String path, int i) {
    return this.addSuffixToKey(path) + "." + i;
  }

  @Override
  public Clustering precompute(PrecomputeData pd) {
    super.precompute(pd);
    if (this.getCountPrecompute() > 1) {
      return this;
    }

    this.initClusters();
    this.initAdjacency();
    for (int i = 0; i < this.number; ++i) {
      pd.setIntegerList(
          this.addSuffixToKey(PD_ADJACENCY, i),
          this.adjacency.get(i));
    }
    return this;
  }

  @Override
  public Clustering resume(PrecomputeData pd) {
    super.resume(pd);
    if (this.getCountResume() > 1) {
      return this;
    }

    this.number = this.clusterer.getClusterNumber();
    for (EntityID agent : this.worldInfo.getEntityIDsOfType(this.myUrn)) {
      final int i = this.clusterer.getClusterIndex(agent);
      Collection<EntityID> members =
          this.clusterer.getClusterEntityIDs(i);
      this.assignees.put(i, agent);
      this.clusters.put(i, new Cluster(agent, members));
      List<Integer> neighbors =
          pd.getIntegerList(this.addSuffixToKey(PD_ADJACENCY, i));
      this.adjacency.put(i, neighbors);
    }
    return this;
  }

  @Override
  public Clustering preparate() {
    super.preparate();
    if (this.getCountPreparate() > 1) {
      return this;
    }
    this.initClusters();
    this.initAdjacency();
    return this;
  }

  // just overrides {{{
  @Override
  public int getClusterNumber() {
    return this.clusterer.getClusterNumber();
  }

  @Override
  public int getClusterIndex(EntityID id) {
    return this.clusterer.getClusterIndex(id);
  }

  @Override
  public int getClusterIndex(StandardEntity se) {
    return this.clusterer.getClusterIndex(se);
  }
  // }}}

  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    if (i < 0) {
      return null;
    }

    if (0 <= i && i < this.number) {
      return this.clusterer.getClusterEntityIDs(i);
    }

    if (this.caches.containsKey(i)) {
      return this.caches.get(i).members();
    }

    final int index = this.getMergeIndex(i, this.number);
    final int level = this.getMergeLevel(i, this.number);
    Cluster ret = this.mergeOneToLevel(index, level);
    // visual debug {{{
    Collection<Integer> data  = ret.members().stream()
           .mapToInt(EntityID::getValue)
           .boxed().collect(Collectors.toList());
    this.vdclient.drawAsync(
           this.agentInfo.getID().getValue(),
           "ClusterArea",
           (Serializable) data);
    // }}}
    this.caches.put(i, ret);
    return ret.members();
  }

  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {
    final Collection<EntityID> entities = this.getClusterEntityIDs(i);
    if (entities == null) {
      return null;
    }
    Collection<StandardEntity> ret = entities.stream()
        .map(this.worldInfo::getEntity).collect(Collectors.toList());
    return ret;
  }

  @Override
  public Clustering calc() {
    return this;
  }

  /**
   * INDEX RULE: i = cluster_size * (n-1) + index where, n : level index : 0,… ,(size-1)
   */
  private int getMergeLevel(int i, int size) {
    return (i / size) + 1;
  }

  private int getMergeIndex(int i, int size) {
    return i - size * (i / size);
  }

  private Cluster mergeOneToLevel(int index, int level) {
    List<Integer> openlist = this.clusters.keySet().stream()
        .collect(Collectors.toList());
    Queue<Integer> searchQueue = new ArrayDeque<>();
    openlist.remove(index);
    searchQueue.add(index);
    Cluster target = this.clusters.get(index);
    Cluster ret = new Cluster(
        target.assignee(), target.level(), target.members());
    // merge clusters to target in short-distance order
    while (ret.level() < level) {
      if (searchQueue.isEmpty()) {
        break;
      }

      final int i = searchQueue.poll();
      final Cluster c = this.clusters.get(i);
      final Comparator<Integer> distanceTo =
          Comparator.comparing(j -> this.clusters.get(j).distTo(c));
      final List<Integer> neighbors = openlist.stream()
          .filter(j -> this.adjacency.get(i).contains(j))
          .sorted(distanceTo).collect(Collectors.toList());

      final int shortage = level - ret.level();
      if (neighbors.size() >= shortage) {
        List<Cluster> sub = neighbors.stream()
            .filter(j -> neighbors.indexOf(j) < shortage)
            .map(this.clusters::get)
            .collect(Collectors.toList());
        ret.mergeAll(sub);
        break;
      }
      List<Cluster> full = neighbors.stream().map(this.clusters::get)
          .collect(Collectors.toList());
      ret.mergeAll(full);
      openlist.removeAll(neighbors);
      searchQueue.addAll(neighbors);
    }
    return ret;
  }

  // inner class {{{
  class Cluster {

    private EntityID assignee;
    private int level;
    private List<EntityID> members;
    private Pair<Integer, Integer> centroid;

    public Cluster(EntityID assignee,
        int level, Collection<EntityID> members) {
      this.assignee = assignee;
      this.level = level;
      this.members = new ArrayList<>(members);
      this.centroid = this.centroid();
    }

    public Cluster(EntityID assignee, Collection<EntityID> members) {
      this(assignee, 1, members);
    }

    private Pair<Integer, Integer> centroid() {
      int sumx = 0, sumy = 0;
      for (EntityID id : this.members) {
        Pair<Integer, Integer> loc = worldInfo.getLocation(id);
        sumx += loc.first();
        sumy += loc.second();
      }
      final int size = this.members.size();
      return new Pair<Integer, Integer>(sumx / size, sumy / size);
    }

    public EntityID assignee() {
      return this.assignee;
    }

    public int level() {
      return this.level;
    }

    public List<EntityID> members() {
      return this.members;
    }

    public int cx() {
      return this.centroid.first();
    }

    public int cy() {
      return this.centroid.second();
    }

    public double distTo(Cluster c) {
      return Math.hypot(c.cx() - this.cx(), c.cy() - this.cy());
    }

    public Cluster merge(Cluster c) {
      if (c == null) {
        return this;
      }
      this.level += c.level();
      this.members.addAll(c.members());
      this.members = this.members.stream()
          .distinct().collect(Collectors.toList());
      this.centroid = this.centroid();
      return this;
    }

    public Cluster mergeAll(Collection<Cluster> cs) {
      if (cs == null || cs.isEmpty()) {
        return this;
      }

      for (Cluster c : cs) {
        if (c == null) {
          continue;
        }
        this.level += c.level();
        this.members.addAll(c.members());
      }
      this.centroid = this.centroid();
      return this;
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.assignee, this.level, this.members);
    }

    @Override
    public boolean equals(Object obj) {
      if (!Cluster.class.isInstance(obj)) {
        return false;
      }
      Cluster c = (Cluster) obj;
      boolean b1 = this.assignee.equals(c.assignee());
      boolean b2 = this.level == c.level();
      boolean b3 = this.members.equals(c.members());
      return b1 && b2 && b3;
    }

    @Override
    public String toString() {
      StringJoiner sjmember = new StringJoiner(",");
      this.members.forEach(id -> sjmember.add("" + id));
      String assignee = "Assignee(" + this.assignee + ")";
      String level = "Level(" + this.level + ")";
      //String members = "Level(" + sjmember + ")";
      //StringJoiner sjall = new StringJoiner(",");
      //sjall.add(assignee); sjall.add(level); sjall.add(members);
      //return "[" + sjall + "]";
      return "[" + assignee + "," + level + "]";
    }
  }
  // }}}
}
