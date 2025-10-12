package AIT_2022.module.algorithm;

import adf.core.agent.info.*;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.StaticClustering;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.agent.communication.MessageManager;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import java.util.*;

import static java.util.stream.Collectors.*;
import static java.util.Comparator.*;

import java.awt.Polygon;
// @ DEBUG {{{
import com.mrl.debugger.remote.VDClient;
// }}}

public class KmeansClustering extends StaticClustering {

  private Map<EntityID, Integer> assignment = new HashMap<>();
  private KmeansPP clusterer;
  private int n = 0;
  private StandardEntityURN urn;

  // @ DEBUG {{{
  private VDClient vdclient = VDClient.getInstance();
  // }}}

  public KmeansClustering(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
    this.urn = this.agentInfo.me().getStandardURN();

    // @ DEBUG {{{
    this.vdclient.init();
    // }}}
  }

  @Override
  public Clustering calc() {
    return this;
  }

  @Override
  public int getClusterNumber() {
    return this.n;
  }

  @Override
  public int getClusterIndex(StandardEntity entity) {
    return this.getClusterIndex(entity.getID());
  }

  @Override
  public int getClusterIndex(EntityID id) {
    if (!this.assignment.containsKey(id)) {
      return -1;
    }
    return this.assignment.get(id);
  }

  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {
    if (i < 0 || i >= this.n) {
      return null;
    }

    Collection<EntityID> ids = this.getClusterEntityIDs(i);
    Collection<StandardEntity> ret = new ArrayList<>(ids.size());
    for (EntityID id : ids) {
      ret.add(this.worldInfo.getEntity(id));
    }
    return ret;
  }

  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    if (i < 0 || i >= this.n) {
      return null;
    }
    return this.clusterer.getClusterMembers(i);
  }

  private void initN() {
    switch (this.urn) {
      case FIRE_BRIGADE:
        this.n = this.scenarioInfo.getScenarioAgentsFb();
        break;
      case POLICE_FORCE:
        this.n = this.scenarioInfo.getScenarioAgentsPf();
        break;
      case AMBULANCE_TEAM:
        this.n = this.scenarioInfo.getScenarioAgentsAt();
        break;
      default:
        this.n = 0;
    }
  }

  private void initClusterer() {
    List<StandardEntity> entities = new ArrayList<>(
        this.worldInfo.getEntitiesOfType(
            ROAD, HYDRANT,
            BUILDING, GAS_STATION,
            REFUGE,
            POLICE_OFFICE, FIRE_STATION, AMBULANCE_CENTRE));
    entities.sort(comparing(e -> e.getID().getValue()));

    int size = entities.size();
    EntityID[] is = new EntityID[size];
    double[] xs = new double[size];
    double[] ys = new double[size];

    for (int i = 0; i < size; ++i) {
      Area area = (Area) entities.get(i);
      is[i] = area.getID();
      xs[i] = area.getX();
      ys[i] = area.getY();
    }

    this.clusterer = new KmeansPP(is, xs, ys, this.n);
  }

  private void pair() {
    List<StandardEntity> agents = new ArrayList<>(
        this.worldInfo.getEntitiesOfType(this.urn));
    agents.sort(comparing(e -> e.getID().getValue()));

    int[][] costs = new int[this.n][this.n];
    for (int row = 0; row < this.n; ++row) {
      Human agent = (Human) agents.get(row);
      double x = agent.getX();
      double y = agent.getY();

      for (int col = 0; col < this.n; ++col) {
        double cx = this.clusterer.getClusterX(col);
        double cy = this.clusterer.getClusterY(col);
        costs[row][col] = (int) Math.hypot(cx - x, cy - y);
      }
    }

    int[] result = Hungarian.execute(costs);
    for (int row = 0; row < n; ++row) {
      EntityID id = agents.get(row).getID();
      this.assignment.put(id, result[row]);
    }
  }

  private final static int REP_PRECOMPUTE = 20;

  private final static String MODULE_NAME =
      "AIT_2022.module.algorithm.KmeansClustering";
  private final static String PD_CLUSTER_N = MODULE_NAME + ".n";
  private final static String PD_CLUSTER_M = MODULE_NAME + ".m";
  private final static String PD_CLUSTER_A = MODULE_NAME + ".a";

  private String addSuffixToKey(String path) {
    return path + "." + this.urn;
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

    this.initN();
    this.initClusterer();
    this.clusterer.execute(REP_PRECOMPUTE);
    this.pair();

    pd.setInteger(this.addSuffixToKey(PD_CLUSTER_N), this.n);
    for (EntityID agent : this.assignment.keySet()) {
      int i = this.assignment.get(agent);
      Collection<EntityID> cluster = this.clusterer.getClusterMembers(i);

      pd.setEntityIDList(
          this.addSuffixToKey(PD_CLUSTER_M, i),
          new ArrayList<>(cluster));
      pd.setEntityID(this.addSuffixToKey(PD_CLUSTER_A, i), agent);
    }

    return this;
  }

  @Override
  public Clustering resume(PrecomputeData pd) {
    super.resume(pd);
    if (this.getCountResume() > 1) {
      return this;
    }

    this.n = pd.getInteger(this.addSuffixToKey(PD_CLUSTER_N));
    List<Collection<EntityID>> clusters = new ArrayList<>(this.n);
    for (int i = 0; i < this.n; ++i) {
      List<EntityID> cluster =
          pd.getEntityIDList(this.addSuffixToKey(PD_CLUSTER_M, i));
      EntityID agent =
          pd.getEntityID(this.addSuffixToKey(PD_CLUSTER_A, i));

      clusters.add(cluster);
      this.assignment.put(agent, i);
    }

    this.clusterer = new KmeansPP(this.n, clusters);
    // @ DEBUG {{{
    // this.runVisualDebug();
    // }}}
    return this;
  }

  private final static int REP_PREPARE = 20;

  @Override
  public Clustering preparate() {
    super.preparate();
    if (this.getCountPreparate() > 1) {
      return this;
    }

    this.initN();
    this.initClusterer();
    this.clusterer.execute(REP_PREPARE);
    this.pair();
    return this;
  }

  // @ DEBUG {{{
  private void runVisualDebug()
  {
      if (this.urn != POLICE_FORCE) return;

      EntityID me = this.agentInfo.getID();
      Collection<StandardEntity> cluster =
          this.getClusterEntities(this.getClusterIndex(me));

      ConvexHull convexhull = new ConvexHull();
      for (StandardEntity entity : cluster)
      {
          convexhull.add((Area)entity);
      }

      ArrayList<Polygon> data = new ArrayList<>(1);
      data.add(convexhull.get());
      this.vdclient.draw(me.getValue(), "SamplePolygon", data);
  }
  // }}}
}
