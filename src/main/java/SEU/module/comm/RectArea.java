package SEU.module.comm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.*;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.communication.MessageCoordinator;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.police.ActionClear;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.misc.geometry.*;

import java.awt.geom.Path2D;
import java.awt.geom.Ellipse2D;
import java.util.*;
import java.util.stream.*;

import static java.util.stream.Collectors.*;
import static java.util.Comparator.*;

public class RectArea {
    public Point2D center;   // 中心点
    public double width;
    public double height;
    public double angle;     // 相对于 x 轴的旋转角（单位：弧度）

    public RectArea(Point2D c, double w, double h, double a) {
        this.center = c;
        this.width = w;
        this.height = h;
        this.angle = a;
    }

    // 判断某点是否在矩形内
    public boolean contains(Point2D p) {
        double dx = p.getX() - center.getX();
        double dy = p.getY() - center.getY();

        double cos = Math.cos(-angle);
        double sin = Math.sin(-angle);

        double localX = dx * cos - dy * sin;
        double localY = dx * sin + dy * cos;

        return Math.abs(localX) <= width / 2 && Math.abs(localY) <= height / 2;
    }
}
