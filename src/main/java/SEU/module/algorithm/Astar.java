package SEU.module.algorithm;

import rescuecore2.worldmodel.EntityID;
import java.util.*;

import static java.util.stream.Collectors.*;

public class Astar {

  public static List<EntityID> find(HeuristicNode initial, EntityID end) {
    if (initial == null || end == null) {
      return null;
    }

    Map<EntityID, HeuristicNode> nodes = new HashMap<>();

    final Comparator<HeuristicNode> comparator =
        Comparator.comparingDouble(HeuristicNode::getFvalue);
    Queue<HeuristicNode> queue = new PriorityQueue<>(comparator);
    queue.add(initial);

    while (!queue.isEmpty() && !nodes.containsKey(end)) {
      final HeuristicNode n = queue.remove();
      final EntityID id = n.getID();

      if (nodes.containsKey(id)) {
        continue;
      }
      nodes.put(id, n);

      n.gatherNeighbors().forEach(queue::add);
    }

    LinkedList<HeuristicNode> nodepath = new LinkedList<>();
    HeuristicNode next = nodes.get(end);
    while (next != null) {
      nodepath.push(next);
      next = nodes.get(next.getAncestor());
    }

    return nodepath.stream().map(HeuristicNode::getID).collect(toList());
  }

  public static abstract class HeuristicNode {

    private final EntityID id;
    private final EntityID ancestor;
    private final double gvalue;
    private final double hvalue;

    public HeuristicNode(
        EntityID id, EntityID ancestor, double gvalue, double hvalue) {
      this.id = id;
      this.ancestor = ancestor;
      this.gvalue = gvalue;
      this.hvalue = hvalue;
    }

    public final EntityID getID() {
      return this.id;
    }

    public final EntityID getAncestor() {
      return this.ancestor;
    }

    public abstract Collection<HeuristicNode> gatherNeighbors();

    public final double getGvalue() {
      return this.gvalue;
    }

    public final double getHvalue() {
      return this.hvalue;
    }

    public final double getFvalue() {
      return this.gvalue + this.hvalue;
    }
  }
}
