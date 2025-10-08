package AIT_2022.centralized;

import AIT_2022.module.algorithm.GraphEdgeValidator;
import adf.core.agent.info.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.*;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.*;
import adf.core.component.centralized.CommandExecutor;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import org.locationtech.jts.geom.Geometry;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.standard.entities.*;

import static java.util.stream.Collectors.toList;

import rescuecore2.worldmodel.EntityID;
import java.util.*;

public class AITCommandExecutorPolice extends CommandExecutor<CommandPolice> {

  private static final int ACTION_UNKNOWN = -1;

  private EntityID target;
  private int type;
  private EntityID commander;

  private ExtAction extaction;
  private PathPlanning pathPlanning;

  public AITCommandExecutorPolice(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
    this.type = ACTION_UNKNOWN;

    this.extaction = mm.getExtAction("AIT.PF.CommandExecutorPolice.ExtActionClear");

    this.pathPlanning = mm.getModule("AIT.PF.CommandExecutorPolice.PathPlanning");
  }

  @Override
  public CommandExecutor setCommand(CommandPolice command) {
    final EntityID me = this.agentInfo.getID();
    if (!command.isToIDDefined()) {
      return this;
    }
    if (!me.equals(command.getToID())) {
      return this;
    }

    this.target = command.getTargetID();
    this.type = command.getAction();
    this.commander = command.getSenderID();
    return this;
  }

  @Override
  public CommandExecutor precompute(PrecomputeData pd) {
    super.precompute(pd);
    if (this.getCountPrecompute() >= 2) {
      return this;
    }

    this.extaction.precompute(pd);
    this.pathPlanning.precompute(pd);
    return this;
  }

  @Override
  public CommandExecutor resume(PrecomputeData pd) {
    super.resume(pd);
    if (this.getCountResume() >= 2) {
      return this;
    }

    this.extaction.resume(pd);
    this.pathPlanning.resume(pd);
    return this;
  }

  @Override
  public CommandExecutor preparate() {
    super.preparate();
    if (this.getCountPreparate() >= 2) {
      return this;
    }

    this.extaction.preparate();
    this.pathPlanning.preparate();
    return this;
  }

  @Override
  public CommandExecutor updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }

    this.extaction.updateInfo(mm);
    this.pathPlanning.updateInfo(mm);

    if (this.isCommandCompleted()) {
      mm.addMessage(new MessageReport(true, true, true, this.target));
      this.target = null;
      this.type = ACTION_UNKNOWN;
      this.commander = null;
    }

    return this;
  }

  @Override
  public CommandExecutor calc() {
    this.result = null;
    if (this.target == null) {
      return this;
    }
    if (this.type == ACTION_UNKNOWN) {
      return this;
    }

    this.extaction.setTarget(this.target);
    this.extaction.calc();
    this.result = this.extaction.getAction();
    return this;
  }

  private boolean isCommandCompleted() {
    if (this.needIdle()) {
      return false;
    }
    if (this.target == null) {
      return false;
    }

    final StandardEntity entity = this.worldInfo.getEntity(this.target);
    Area a = null;
    if (entity instanceof Road) {
      a = (Area) entity;
    } else if (entity instanceof Human) {
      a = (Area) this.worldInfo.getPosition((Human) entity);
    } else if (entity instanceof Blockade) {
      a = (Area) this.worldInfo.getPosition((Blockade) entity);
    }

    if (a == null) {
      return true;
    }
    //return a.getBlockades() == null || a.getBlockades().isEmpty();
    return isRoadCompleted(a.getID());
  }

  private static final double AGENT_RADIUS = 500.0;

  private boolean isRoadCompleted(EntityID id) {
    if (id == null) {
      return false;
    }

    final Area area = (Area) this.worldInfo.getEntity(id);
    if (!area.isBlockadesDefined()) {
      return true;
    }

    final Collection<Blockade> blockades =
        area.getBlockades()
            .stream()
            .map(this.worldInfo::getEntity)
            .map(Blockade.class::cast)
            .collect(toList());

    final Geometry passable =
        GraphEdgeValidator.computePassable(area, blockades);

    final List<Line2D> edges =
        area.getEdges()
            .stream()
            .filter(Edge::isPassable)
            .map(Edge::getLine)
            .collect(toList());

    final int n = edges.size();
    for (int i = 0; i < n; ++i) {
      for (int j = i + 1; j < n; ++j) {
        final Line2D l1 = edges.get(i);
        final Line2D l2 = edges.get(j);
        if (l1.getDirection().getLength() < AGENT_RADIUS * 2) {
          continue;
        }
        if (l2.getDirection().getLength() < AGENT_RADIUS * 2) {
          continue;
        }
        if (!GraphEdgeValidator.validate(passable, l1, l2)) {
          return false;
        }
      }
    }

    return true;
  }

  private boolean isEmptyAction(Action action) {
    if (action == null) {
      return true;
    }
    if (action instanceof ActionRest) {
      return true;
    }
    if (action instanceof ActionMove) {
      final ActionMove move = (ActionMove) action;
      final int ax = (int) this.agentInfo.getX();
      final int ay = (int) this.agentInfo.getY();
      final int mx = move.getPosX();
      final int my = move.getPosY();
      return ax == mx && ay == my;
    }
    return false;
  }

  private boolean needIdle() {
    final int time = this.agentInfo.getTime();
    final int ignored = this.scenarioInfo.getKernelAgentsIgnoreuntil();
    return time < ignored;
  }
}
