package SEU.module.algorithm.PathPlanning;

import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import rescuecore2.worldmodel.*;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.misc.geometry.*;
import org.locationtech.jts.geom.*;

import SEU.module.algorithm.Astar;
import SEU.module.algorithm.Couple;
import SEU.module.algorithm.GraphEdgeValidator;

import java.util.*;
import java.util.stream.*;

import static java.util.stream.Collectors.*;
import static java.util.Comparator.*;


public class SEUPathPlanning extends PathPlanning {

  private List<EntityID> result;

  private EntityID from;
  private EntityID destination;

  private Map<EntityID, Set<Couple<EntityID>>> invalidz = new HashMap<>();
  private final static double INVALID_PENALTY = 15.0;

  private StaticClustering highways;
  private Clustering failedMove;
  private Clustering stuckedHumans;

  public SEUPathPlanning(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    this.highways = mm.getModule("SEU.Algorithm.SEUPathPlanning.Highways");
    this.registerModule(this.highways);

    this.failedMove =
        mm.getModule("SEU.Algorithm.SEUPathPlanning.FailedMove");
    this.registerModule(this.failedMove);

    this.stuckedHumans =
        mm.getModule("SEU.Algorithm.SEUPathPlanning.StuckHumans");
    this.registerModule(this.stuckedHumans);
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
        .stream()
        .min(comparing(i -> this.worldInfo.getDistance(i, me)))
        .orElse(null);
    return this;
  }

  @Override
  public PathPlanning updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    final Set<EntityID> changes =
        this.worldInfo.getChanged().getChangedEntities();

    for (Area area : this.gatherRoads(changes)) {
      this.reflectEdgeStates(area);
    }

//    final Human human = (Human) this.agentInfo.me();
//    if (this.cannotReach() && human.getBuriedness() == 0) {
//      final EntityFilter filter
//          = new EntityFilter(this.worldInfo, this.agentInfo);
//      final EntityID toID = filter.getNearyPF();
//      final EntityID positionID = this.agentInfo.getPosition();
//      for (boolean useRadio : new boolean[]{true, false}) {
//        final CommunicationMessage command = new CommandPolice(
//            useRadio, StandardMessagePriority.HIGH,
//            toID, positionID, CommandPolice.ACTION_CLEAR);
//        mm.addMessage(command, false);
//      }
//    }

    return this;
  }

  @Override
  public PathPlanning calc() {
    this.result = null;
    this.result = Astar.find(new Node(this.from), this.destination);
    return this;
  }

  private Collection<Area> gatherRoads(Collection<EntityID> ids) {
    final Stream<Area> ret = ids
        .stream()
        .map(this.worldInfo::getEntity)
        .filter(Road.class::isInstance)
        .map(Road.class::cast);

    return ret.collect(toList());
  }

  private void reflectEdgeStates(Area area) {
    final EntityID id = area.getID();
    final Collection<Blockade> blockades = this.worldInfo.getBlockades(id);
    final Geometry passable =
        GraphEdgeValidator.computePassable(area, blockades);

    final Stream<Couple<EntityID>> invalids =
        this.gatherEdges(area)
            .stream()
            .filter(e -> !this.validateEdge(area, passable, e));
    this.invalidz.put(id, invalids.collect(toSet()));

    if (blockades.isEmpty()) {
      this.invalidz.remove(id);
    }
  }

  private Set<Couple<EntityID>> gatherEdges(Area area) {
    final List<EntityID> neighbors = area.getNeighbours();
    final int n = neighbors.size();

    Set<Couple<EntityID>> edges = new HashSet<>();

    for (int i = 0; i < n - 1; ++i) {
      for (int j = i + 1; j < n; ++j) {
        edges.add(new Couple<>(neighbors.get(i), neighbors.get(j)));
      }
    }

    final EntityID id = area.getID();
    final boolean position = id.equals(this.agentInfo.getPosition());
    for (int i = 0; i < n && position; ++i) {
      edges.add(new Couple<>(neighbors.get(i), null));
    }

    return edges;
  }

  private boolean validateEdge(
      Area area, Geometry passable, Couple<EntityID> edge) {
    final Line2D location = new Line2D(this.getPoint(), this.getPoint());

    final Line2D l1 = edge.elem1 == null ?
        location : area.getEdgeTo(edge.elem1).getLine();
    final Line2D l2 = edge.elem2 == null ?
        location : area.getEdgeTo(edge.elem2).getLine();

    return GraphEdgeValidator.validate(passable, l1, l2);
  }

  private Point2D getPoint() {
    final double x = this.agentInfo.getX();
    final double y = this.agentInfo.getY();
    return new Point2D(x, y);
  }

  private boolean isInvalidEdge(EntityID id, EntityID prev, EntityID next) {
    if (!this.invalidz.containsKey(id)) {
      return false;
    }

    final Set<Couple<EntityID>> invalids = this.invalidz.get(id);
    if (invalids.contains(new Couple<>(prev, next))) {
      return true;
    }
    if (invalids.contains(new Couple<>(next, prev))) {
      return true;
    }
    return false;
  }

  private boolean cannotReach() {
    final EntityID me = this.agentInfo.getID();
    final StandardEntityURN urn = this.agentInfo.me().getStandardURN();

    if (urn == POLICE_FORCE) {
      return false;
    }

    this.failedMove.calc();
    this.stuckedHumans.calc();

    final boolean failed = this.failedMove.getClusterIndex(me) >= 0;
    final boolean stucked = this.stuckedHumans.getClusterIndex(me) >= 0;
    return stucked || failed;
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
    double d = this.computeDistance(id, ancestor.getID());
    if (this.isHighway(ancestor.getID())) {
      return d;
    }

    d *= 3.0;
    if (this.isInvalidEdge(id, ancestor)) {
      d *= INVALID_PENALTY;
    }
    return d;
  }

  private double computeHeuristic(EntityID id) {
    return this.worldInfo.getDistance(id, this.destination);
  }

  private Collection<EntityID> getNeighbors(EntityID id) {
    final Area area = (Area) this.worldInfo.getEntity(id);
    return area.getNeighbours();
  }

  private double computeDistance(EntityID id, EntityID ancestor) {
    return this.worldInfo.getDistance(id, ancestor);
    //final Area area1 = (Area)this.worldInfo.getEntity(ancestor);
    //final Area area2 = (Area)this.worldInfo.getEntity(id);

    //final Point2D p1 = new Point2D(area1.getX(), area1.getY());
    //final Point2D p2 = area1.getEdgeTo(id).getLine().getPoint(0.5);
    //final Point2D p3 = new Point2D(area2.getX(), area2.getY());

    //double ret = 0.0;
    //ret += GeometryTools2D.getDistance(p1, p2);
    //ret += GeometryTools2D.getDistance(p2, p3);
    //return ret;
  }

  private boolean isInvalidEdge(EntityID id, Astar.HeuristicNode ancestor) {
    final EntityID node = ancestor.getID();
    final EntityID prev = ancestor.getAncestor();
    final EntityID next = id;
    return this.isInvalidEdge(node, prev, next);
  }

  private boolean isHighway(EntityID id) {
    final int time = this.agentInfo.getTime();
    if (time < 30) {
      return false;
    }

    return this.highways.getClusterIndex(id) >= 0;
  }
  // }}}
}
