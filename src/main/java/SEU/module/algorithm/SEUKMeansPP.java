package SEU.module.algorithm;

import rescuecore2.worldmodel.EntityID;
import java.util.*;

import static java.util.Comparator.*;

public class SEUKMeansPP {

  private EntityID[] targets;
  private double[] xs;
  private double[] ys;

  private Cluster[] result;

  private int n;

  private static final int COMMON_SEED = 123456789;

  public SEUKMeansPP(
      EntityID[] targets, double[] xs, double[] ys, int n) {
    this.targets = targets;
    this.xs = xs;
    this.ys = ys;
    this.n = n;
  }

  public SEUKMeansPP(
      int n, List<Collection<EntityID>> memberz) {
    this.result = new Cluster[n];
    this.n = n;

    for (int i = 0; i < n; ++i) {
      Collection<EntityID> members = memberz.get(i);
      this.result[i] = new Cluster(members);
    }
  }

  public void execute(int rep) {
    this.result = init(this.targets, this.xs, this.ys, this.n);

    for (int i = 0; i < rep; ++i) {
      Arrays.stream(this.result).forEach(Cluster::clearMembers);
      for (int j = 0; j < this.targets.length; ++j) {
        assign(this.result, this.targets[j], this.xs[j], this.ys[j]);
      }
      Arrays.stream(this.result).forEach(Cluster::updateCenter);
    }
  }

  public int getClusterNumber() {
    return this.result.length;
  }

  public double getClusterX(int i) {
    return this.result[i].getCX();
  }

  public double getClusterY(int i) {
    return this.result[i].getCY();
  }

  public Collection<EntityID> getClusterMembers(int i) {
    return this.result[i].getMembers();
  }

  private static Cluster[] init(
      EntityID[] targets, double[] xs, double[] ys, int n) {
    Cluster[] ret = new Cluster[n];
    for (int i = 0; i < n; ++i) {
      ret[i] = new Cluster();
    }

    Random random = new Random(COMMON_SEED);

    int r = random.nextInt(targets.length);
    ret[0].addMember(targets[r], xs[r], ys[r]);
    ret[0].updateCenter();

    boolean[] assigned = new boolean[targets.length];
    assigned[r] = true;
    for (int i = 0; i < n - 1; ++i) {
      double[] ds = new double[targets.length];
      double sumd = 0.0;
      for (int j = 0; j < targets.length; ++j) {
        if (assigned[j]) {
          continue;
        }

        double cx = ret[i].getCX();
        double cy = ret[i].getCY();
        double x = xs[j];
        double y = ys[j];
        ds[j] = Math.hypot(x - cx, y - cy);
        sumd += ds[j];
      }

      for (int j = 0; j < targets.length; ++j) {
        ds[j] /= sumd;
      }

      double p = random.nextDouble();
      double accp = 0.0;
      for (int j = 0; j < targets.length; ++j) {
        if (assigned[j]) {
          continue;
        }

        accp += ds[j];
        if (p <= accp) {
          ret[i + 1].addMember(targets[j], xs[j], ys[j]);
          ret[i + 1].updateCenter();
          assigned[j] = true;
          break;
        }
      }
    }

    return ret;
  }

  private static void assign(
      Cluster[] clusters, EntityID id, double x, double y) {
    Optional<Cluster> cluster = Arrays.stream(clusters)
        .min(comparing(c -> {
          double cx = c.getCX();
          double cy = c.getCY();
          return Math.hypot(cx - x, cy - y);
        }));

    cluster.get().addMember(id, x, y);
  }

  private static class Cluster {

    private double cx = 0.0;
    private double cy = 0.0;

    private Collection<EntityID> members = new LinkedList<>();

    private double sumx = 0.0;
    private double sumy = 0.0;

    public Cluster() {
    }

    public Cluster(Collection<EntityID> members) {
      this.members = members;
    }

    public void addMember(EntityID id, double x, double y) {
      this.members.add(id);
      this.sumx += x;
      this.sumy += y;
    }

    public Collection<EntityID> getMembers() {
      return new ArrayList<>(this.members);
    }

    public void clearMembers() {
      this.members.clear();
      this.sumx = 0.0;
      this.sumy = 0.0;
    }

    public void updateCenter() {
      if (this.members.isEmpty()) {
        return;
      }

      this.cx = this.sumx / this.members.size();
      this.cy = this.sumy / this.members.size();
    }

    public double getCX() {
      return this.cx;
    }

    public double getCY() {
      return this.cy;
    }
  }
}
