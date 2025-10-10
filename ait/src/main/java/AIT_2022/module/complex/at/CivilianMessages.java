package AIT_2022.module.complex.at;

import java.util.*;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.centralized.CommandFire;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.AbstractModule;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import AIT_2022.module.complex.common.EntityFilter;
import AIT_2022.module.complex.common.LifeXpectancy;

public class CivilianMessages extends AbstractModule {

  private boolean needRescueForMe = false;
  private Set<EntityID> rescueCivTask = new HashSet<>();
  private Set<EntityID> rescueAgentTask = new HashSet<>();
  private Set<EntityID> loadCivTask = new HashSet<>();
  private Set<EntityID> sendTarget = new HashSet<>();
  private Set<EntityID> receiveLoadTask = new HashSet<>();

  private final boolean VOICE = false;
  private final boolean RADIO = true;

  public CivilianMessages(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm,
      DevelopData dd) {
    super(ai, wi, si, mm, dd);
    this.worldInfo = wi;
    this.agentInfo = ai;
  }

  @Override
  public AbstractModule updateInfo(MessageManager mm) {
    this.receiveMessage(mm);
    this.updateTask();
    this.sendMessage(mm);
    return this;
  }

  @Override
  public AbstractModule calc() {
    final EntityID me = this.agentInfo.getID();
    this.needRescueForMe = this.rescueTarget(me);
    if (needRescueForMe) {
      return this;
    }

    Set<EntityID> changes = this.worldInfo.getChanged().getChangedEntities();
    changes.add(me);
    Map<EntityID, Set<EntityID>> civPos = new HashMap<>();
    Map<EntityID, Set<EntityID>> ambPos = new HashMap<>();
    Map<EntityID, Set<EntityID>> fbPos = new HashMap<>();

    for (EntityID e : changes) {
      final StandardEntity se = this.worldInfo.getEntity(e);
      if (!(se instanceof Human)) {
        continue;
      }
      final StandardEntity targetPosition = this.worldInfo.getPosition(e);
      if (targetPosition.getStandardURN().equals(REFUGE)) {
        continue;
      }
      final boolean rescueTarget = this.rescueTarget(e);
      final boolean loadTarget = this.loadTarget(e);
      final StandardEntityURN targetURN = se.getStandardURN();
      final EntityID targetPositionID = targetPosition.getID();
      if (targetURN.equals(AMBULANCE_TEAM)) {
        if (rescueTarget) {
          this.rescueAgentTask.add(e);
        } else {
          Set<EntityID> AMBs = ambPos.get(targetPositionID);
          if (AMBs == null) {
            AMBs = new HashSet<>();
          }
          AMBs.add(e);
          ambPos.put(targetPositionID, AMBs);
        }
      } else if (targetURN.equals(FIRE_BRIGADE)) {
        if (rescueTarget) {
          this.rescueAgentTask.add(e);
        } else {
          Set<EntityID> FBs = fbPos.get(targetPositionID);
          if (FBs == null) {
            FBs = new HashSet<>();
          }
          FBs.add(e);
          fbPos.put(targetPositionID, FBs);
        }
      } else if (targetURN.equals(POLICE_FORCE)) {
        if (rescueTarget) {
          this.rescueAgentTask.add(e);
        }
      } else if (targetURN.equals(CIVILIAN)) {
        if (rescueTarget) {
          Set<EntityID> CIVs = civPos.get(targetPositionID);
          if (CIVs == null) {
            CIVs = new HashSet<>();
          }
          CIVs.add(e);
          civPos.put(targetPositionID, CIVs);
          this.rescueCivTask.add(e);
        } else if (loadTarget) {
          this.loadCivTask.add(e);
        }
      }
    }
    return this;
  }

  public EntityID getResult() {
    return null;
  }

  private boolean rescueTarget(EntityID e) {
    final Human h = (Human) this.worldInfo.getEntity(e);
    if (this.isFailedTask(h) == true) {
      return false;
    }
    final int hp = h.getHP();
    final int buried = h.getBuriedness();
    return (hp * buried != 0);
  }

  private boolean loadTarget(EntityID e) {
    final Human h = (Human) this.worldInfo.getEntity(e);
    if (h == null) {
      return false;
    }
    final int hp = h.getHP();
    final int buried = h.getBuriedness();
    return (hp > 0 && buried == 0);
  }

  private boolean canSave(EntityID id, int n) {
    final StandardEntity s = this.worldInfo.getEntity(id);
    if (((Human) s).getBuriedness() == 0 || ((Human) s).getHP() == 0
        || ((Human) s).getDamage() == 0) {
      return false;
    }
    final LifeXpectancy lx = new LifeXpectancy((Human) s);
    final int ttl = lx.getLifeXpectancy();
    return (ttl - this.rescueTime(id, n) > 0);
  }

  private int rescueTime(EntityID id, int n) {
    return ((Human) this.worldInfo.getEntity(id)).getBuriedness() / n;
  }

  private void handleMessage(MessageCivilian msg) {
    final EntityID targetID = msg.getAgentID();
    if (this.rescueTarget(targetID)) {
      this.rescueCivTask.add(targetID);
    } else {
      this.rescueCivTask.remove(targetID);
    }
  }

  private void handleMessage(MessageFireBrigade msg) {
    final EntityID targetID = msg.getAgentID();
    if (this.rescueTarget(targetID)) {
      this.rescueAgentTask.add(targetID);
    } else {
      this.rescueAgentTask.remove(targetID);
    }
  }

  private void handleMessage(MessageAmbulanceTeam msg) {
    final EntityID targetID = msg.getAgentID();
    if (this.rescueTarget(targetID)) {
      this.rescueAgentTask.add(targetID);
    } else {
      this.rescueAgentTask.remove(targetID);
    }
  }

  private void handleMessage(MessagePoliceForce msg) {
    final EntityID targetID = msg.getAgentID();
    if (this.rescueTarget(targetID)) {
      this.rescueAgentTask.add(targetID);
    } else {
      this.rescueAgentTask.remove(targetID);
    }
  }

  private void handleMessage(CommandAmbulance msg) {
    if (msg.getToID().getValue()
        - this.agentInfo.getID().getValue() != 0) {
      return;
    }
    if (this.agentInfo.someoneOnBoard() != null) {
      this.receiveLoadTask.add(msg.getTargetID());
    }
  }

  private void receiveMessage(MessageManager mm) {
    final List<CommunicationMessage> cmsg = mm.getReceivedMessageList(MessageCivilian.class);
    for (CommunicationMessage tmp : cmsg) {
      final MessageCivilian message = (MessageCivilian) tmp;
      this.handleMessage(message);
    }
//    final List<CommunicationMessage> amsg = mm.getReceivedMessageList(MessageAmbulanceTeam.class);
//    for (CommunicationMessage tmp : amsg) {
//      final MessageAmbulanceTeam message = (MessageAmbulanceTeam) tmp;
//      this.handleMessage(message);
//    }
//    final List<CommunicationMessage> fmsg = mm.getReceivedMessageList(MessageFireBrigade.class);
//    for (CommunicationMessage tmp : fmsg) {
//      final MessageFireBrigade message = (MessageFireBrigade) tmp;
//      this.handleMessage(message);
//    }
//    final List<CommunicationMessage> pmsg = mm.getReceivedMessageList(MessagePoliceForce.class);
//    for (CommunicationMessage tmp : pmsg) {
//      final MessagePoliceForce message = (MessagePoliceForce) tmp;
//      this.handleMessage(message);
//    }
//    final List<CommunicationMessage> camb = mm.getReceivedMessageList(CommandAmbulance.class);
//    for (CommunicationMessage tmp : camb) {
//      final CommandAmbulance message = (CommandAmbulance) tmp;
//      this.handleMessage(message);
//    }
  }

  private void sendMessage(MessageManager mm) {
    final EntityFilter filter
        = new EntityFilter(this.worldInfo, this.agentInfo);
    final EntityID FB = filter.getNearyFB();
    if (FB != null && !this.rescueAgentTask.isEmpty()) {
      final Iterator<EntityID> i = this.rescueAgentTask.iterator();
      final EntityID e = i.next();
      final StandardEntity se = this.worldInfo.getEntity(e);
      final boolean sendRadio = this.agentInfo.getID().getValue() % 10
          == this.agentInfo.getTime() % 10;
      if (se instanceof AmbulanceTeam) {
        mm.addMessage(new MessageAmbulanceTeam(
            VOICE, (AmbulanceTeam) se, CommandAmbulance.ACTION_MOVE, e));
        if (sendRadio) {
          mm.addMessage(new MessageAmbulanceTeam(
              VOICE, (AmbulanceTeam) se, CommandAmbulance.ACTION_MOVE, e));
        }
      } else if (se instanceof FireBrigade) {
        mm.addMessage(new MessageFireBrigade(
            VOICE, (FireBrigade) se, CommandFire.ACTION_MOVE, e));
        if (sendRadio) {
          mm.addMessage(new MessageFireBrigade(
              VOICE, (FireBrigade) se, CommandFire.ACTION_MOVE, e));
        }
      } else if (se instanceof PoliceForce) {
        mm.addMessage(new MessagePoliceForce(
            VOICE, (PoliceForce) se, CommandPolice.ACTION_MOVE, e));
        if (sendRadio) {
          mm.addMessage(new MessagePoliceForce(
              VOICE, (PoliceForce) se, CommandPolice.ACTION_MOVE, e));
        }
      }
      i.remove();
      return;
    }

    if (!this.receiveLoadTask.isEmpty()) {
      final EntityID AT = filter.getNearyAT();
      if (AT != null && !this.receiveLoadTask.isEmpty()) {
        final Iterator<EntityID> i = this.receiveLoadTask.iterator();
        final EntityID targetID = i.next();
        mm.addMessage(new CommandAmbulance(
            VOICE, AT, targetID, CommandAmbulance.ACTION_MOVE));
        i.remove();
        return;
      }
    }

    Set<EntityID> targets = new HashSet<>(this.rescueCivTask);
    targets.removeAll(this.sendTarget);
    if (targets.isEmpty()) {
      targets = new HashSet<>(this.sendTarget);
    }
    if (targets.isEmpty()) {
      return;
    }
    final Civilian c = (Civilian) this.worldInfo.getEntity(targets.iterator().next());
    mm.addMessage(new MessageCivilian(VOICE, c));
    this.sendTarget.add(c.getID());
  }

  private void updateTask() {
    Iterator<EntityID> i = this.rescueCivTask.iterator();
    while (i.hasNext()) {
      if (!this.rescueTarget(i.next())) {
        i.remove();
      }
    }
    i = this.rescueAgentTask.iterator();
    while (i.hasNext()) {
      if (!this.rescueTarget(i.next())) {
        i.remove();
      }
    }
    i = this.loadCivTask.iterator();
    while (i.hasNext()) {
      if (!this.loadTarget(i.next())) {
        i.remove();
      }
    }
    i = this.sendTarget.iterator();
    while (i.hasNext()) {
      if (!this.rescueTarget(i.next())) {
        i.remove();
      }
    }
    // if (this.rescueAgentTask.isEmpty())
  }

  private EntityID getPriorityTask(Set<EntityID> tasks) {
    EntityID ret = null;
    double priority = Double.MAX_VALUE;
    for (EntityID e : tasks) {
      final Human h = (Human) this.worldInfo.getEntity(e);
      final Pair<Integer, Integer> p = this.worldInfo.getLocation(e);
      final double distance = Math.abs(this.agentInfo.getX() - p.first())
          + Math.abs(this.agentInfo.getY() - p.second());
      final double cost1 = Math.sqrt(distance);
      final LifeXpectancy lx = new LifeXpectancy(h);
      final double cost2 = lx.getLifeXpectancy() * 1.0;
      final double cost = cost1 + cost2;
      if (priority > cost) {
        priority = cost;
        ret = e;
      }
    }
    return ret;
  }

  private boolean isFailedTask(Human civilian) {
    if (civilian.getPosition() == null) {
      return false;
    }
    if (!this.worldInfo.getEntity(civilian.getPosition()).getStandardURN().equals(REFUGE)) {
      return false;
    }
    return true;
  }
}