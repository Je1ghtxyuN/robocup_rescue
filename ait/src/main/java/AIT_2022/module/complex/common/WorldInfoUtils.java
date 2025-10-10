package AIT_2022.module.complex.common;

import java.util.Set;
import java.util.stream.Collectors;

import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.WorldInfo;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

import java.util.HashSet;

public class WorldInfoUtils {

  private WorldInfo wi;
  private AgentInfo ai;
  private StandardEntityURN type;
  private int number;

  private WorldInfoUtils(WorldInfo wi, AgentInfo ai) {
    this.wi = wi;
    this.ai = ai;
  }

  public static WorldInfoUtils build(WorldInfo wi, AgentInfo ai) {
    return new WorldInfoUtils(wi, ai);
  }

  public WorldInfoUtils type(StandardEntityURN type) {
    this.type = type;
    return this;
  }

  public WorldInfoUtils number(int number) {
    this.number = number;
    return this;
  }

  public Set<EntityID> getEntitieIDs() {
    Set<EntityID> entityIDs = (Set<EntityID>) this.wi.getEntityIDsOfType(type);

    return entityIDs.stream()
        .filter(this.wi.getChanged().getChangedEntities()::contains)
        .collect(Collectors.toSet());
  }

  public Set<Entity> getEntities() {
    return new HashSet<>();
  }

}
