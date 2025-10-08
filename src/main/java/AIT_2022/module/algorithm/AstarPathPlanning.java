package AIT_2022.module.algorithm;

import adf.core.component.module.algorithm.PathPlanning;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.agent.communication.MessageManager;
import rescuecore2.worldmodel.*;
import rescuecore2.standard.entities.*;
import java.util.*;

import static java.util.Comparator.*;

import java.util.stream.*;

import static java.util.stream.Collectors.*;

// @ DEBUG {{{
import com.mrl.debugger.remote.VDClient;
// }}}

public class AstarPathPlanning extends PathPlanning {

  private List<EntityID> result;

  private EntityID from;
  private EntityID destination;

  public AstarPathPlanning(
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
    return this.worldInfo.getDistance(id, this.destination);
  }

  private Collection<EntityID> getNeighbors(EntityID id) {
    final Area area = (Area) this.worldInfo.getEntity(id);
    return area.getNeighbours();
  }

  private double computeDistance(EntityID id, EntityID ancestor) {
    return this.worldInfo.getDistance(id, ancestor);
  }
  // }}}
}
