package AIT_2022.module.algorithm;

import adf.core.component.module.algorithm.PathPlanning;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.agent.communication.MessageManager;
import rescuecore2.worldmodel.*;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import java.util.*;

import static java.util.Comparator.*;

import java.util.stream.*;

import static java.util.stream.Collectors.*;

// @ DEBUG {{{
import com.mrl.debugger.remote.VDClient;
// }}}

public class RoadwidthAstarPathPlanning extends PathPlanning {

  private List<EntityID> result;

  private EntityID from;
  private EntityID destination;

  public RoadwidthAstarPathPlanning(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
  }

  @Override
  public List<EntityID> getResult() {
    return this.result;
  }

  @Override
  public PathPlanning setFrom(EntityID id) {
    this.from = id;
    return this;
  }

  @Override
  public PathPlanning setDestination(Collection<EntityID> destinations) {
    if (destinations == null) {
      this.destination = null;
      return this;
    }

    final EntityID me = this.agentInfo.getID();
    this.destination = destinations
        .stream().min(comparing(i -> this.worldInfo.getDistance(i, me)))
        .orElse(null);
    return this;
  }

  @Override
  public PathPlanning updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    return this;
  }

  @Override
  public PathPlanning calc() {
    this.result = null;
    this.result = Astar.find(new Node(this.from), this.destination);
    return this;
  }

  // @ ASTAR {{{
  private class Node extends Astar.HeuristicNode {

    public Node build(EntityID id) {
      final double gvalue = this.getGvalue() + computeCost(id, this);
      final double hvalue = computeHeuristic(id);
      return new Node(id, this, gvalue, hvalue);
    }

    public Node(EntityID id, Node ancestor, double gvalue, double hvalue) {
      super(id, ancestor.getID(), gvalue, hvalue);
    }

    public Node(EntityID id) {
      super(id, null, 0.0, computeHeuristic(id));
    }

    @Override
    public Collection<Astar.HeuristicNode> gatherNeighbors() {
      final Collection<EntityID> neighbors = getNeighbors(this.getID());
      return neighbors.stream().map(this::build).collect(toList());
    }
  }

  private double computeCost(EntityID id, Astar.HeuristicNode ancestor) {
    final double d = this.computeDistance(id, ancestor.getID());
    return d;
  }

  private double computeHeuristic(EntityID id) {
    return this.worldInfo.getDistance(id, this.destination)
        + additionalPoint(id) / 2;
  }

  private Collection<EntityID> getNeighbors(EntityID id) {
    final Area area = (Area) this.worldInfo.getEntity(id);
    return area.getNeighbours();
  }

  private double computeDistance(EntityID id, EntityID ancestor) {
    return this.worldInfo.getDistance(id, ancestor);
  }
  // }}}

  private double additionalPoint(EntityID id) {
    Area area = (Area) this.worldInfo.getEntity(id);
    List<EntityID> ns = area.getNeighbours();
    int nSize = ns.size();
    double dsum = 0.0;
    for (EntityID n : ns) {
      Edge e = area.getEdgeTo(n);
      double l = Math.hypot(e.getStartX() - e.getEndX(), e.getStartY() - e.getEndY());
      dsum += l;
    }
    return calcArea(area) / (dsum / nSize);
  }

  private double calcArea(Area a) {
    final int[] apexs = a.getApexList();
    final int apexNum = apexs.length / 2;
    Point2D start = new Point2D(0.0, 0.0);
    Point2D middle = new Point2D(0.0, 0.0);
    Point2D end = new Point2D(0.0, 0.0);
    List<Point2D> points = new ArrayList<>();
    double menseki = 0.0;
    for (int i = 0; i < apexNum; i++) {
      points.add(new Point2D(apexs[i * 2], apexs[(i * 2) + 1]));
      if (i == 0) {
        start = new Point2D(apexs[i * 2], apexs[(i * 2) + 1]);
      } else if (i == 1) {
        middle = new Point2D(apexs[i * 2], apexs[(i * 2) + 1]);
      } else {
        end = new Point2D(apexs[i * 2], apexs[(i * 2) + 1]);
        double x = Math.hypot(start.getX() - middle.getX(), start.getY() - middle.getY());
        double y = Math.hypot(middle.getX() - end.getX(), middle.getY() - end.getY());
        double z = Math.hypot(end.getX() - start.getX(), end.getY() - start.getY());
        double s = (x + y + z) / 2;
        double t = Math.sqrt(s * (s - z) * (s - y) * (s - z));
        menseki += t;
        middle = new Point2D(apexs[i * 2], apexs[(i * 2) + 1]);
      }
    }
    return menseki;
  }
}
