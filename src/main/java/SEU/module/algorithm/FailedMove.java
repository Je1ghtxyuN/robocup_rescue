package SEU.module.algorithm;

import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.misc.geometry.*;
import java.util.*;
import java.util.stream.*;

import static java.util.stream.Collectors.*;

public class FailedMove extends DynamicClustering {

  private boolean failed = false;
  private boolean called = false;

  private List<Point2D> history = new LinkedList<>();

  public FailedMove(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
  }

  @Override
  public Clustering updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    this.history.add(0, this.getPoint());
    while (this.history.size() > 2) {
      this.history.remove(2);
    }

    this.called = false;
    return this;
  }

  @Override
  public int getClusterNumber() {
    return this.failed ? 1 : 0;
  }

  @Override
  public int getClusterIndex(EntityID id) {
    final EntityID me = this.agentInfo.getID();
    return me.equals(id) && this.failed ? 0 : -1;
  }

  @Override
  public int getClusterIndex(StandardEntity entity) {
    return this.getClusterIndex(entity.getID());
  }

  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    final EntityID me = this.agentInfo.getID();
    return i == 0 && this.failed ? Collections.singleton(me) : null;
  }

  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {
    final Collection<EntityID> ids = this.getClusterEntityIDs(i);
    if (ids == null) {
      return null;
    }
    return ids.stream().map(this.worldInfo::getEntity).collect(toList());
  }

  @Override
  public Clustering calc() {
    if (this.called || this.needIdle()) {
      return this;
    }

    this.called = true;
    this.failed = this.isMoveFailed();
    return this;
  }

  private boolean needIdle() {
    final int time = this.agentInfo.getTime();
    final int ignored = this.scenarioInfo.getKernelAgentsIgnoreuntil();
    return time <= ignored;
  }

  private static final double AGENT_RADIUS = 500.0;

  private boolean isMoveFailed() {
    final int time = this.agentInfo.getTime();
    final Action action = this.agentInfo.getExecutedAction(time - 1);
    if (!ActionMove.class.isInstance(action)) {
      return false;
    }

    final ActionMove move = (ActionMove) action;
    if (this.isPositionChanged(move)) {
      return false;
    }

    double d1 = Double.POSITIVE_INFINITY;
    final Point2D point0 = this.history.get(0);
    final Point2D point1 = this.history.get(1);
    if (move.getUsePosition()) {
      final Point2D destination = this.getPoint(move);
      d1 = GeometryTools2D.getDistance(point0, destination);
    }

    final double d2 = GeometryTools2D.getDistance(point0, point1);
    return d1 > AGENT_RADIUS && d2 <= AGENT_RADIUS * 6;
  }

  private Point2D getPoint() {
    final double x = this.agentInfo.getX();
    final double y = this.agentInfo.getY();
    return new Point2D(x, y);
  }

  private Point2D getPoint(ActionMove move) {
    final double x = move.getPosX();
    final double y = move.getPosY();
    return new Point2D(x, y);
  }

  private boolean isPositionChanged(ActionMove move) {
    final EntityID position = this.agentInfo.getPosition();
    final List<EntityID> path = new ArrayList<>(move.getPath());
    return !path.isEmpty() && !position.equals(path.get(0));
  }
}
