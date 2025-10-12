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

public class SEUMessageCoordinator extends MessageCoordinator {

    private SEUChannelSubscriber seuChannelSubscriber;
    private static int ALLOWED_TO_SEND_DISTANCE_THRESHOLD = 10000;
    private Logger logger;

    public SEUMessageCoordinator() {
        this.seuChannelSubscriber = new SEUChannelSubscriber();
    }

    @Override
    public void coordinate(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo, MessageManager messageManager,
                           ArrayList<CommunicationMessage> sendMessageList, List<List<CommunicationMessage>> channelSendMessageList) {

        if (seuChannelSubscriber.getSendMessageAgentsRatio() == 0) {
            seuChannelSubscriber.initSendMessageAgentsRatio(worldInfo, scenarioInfo);
            logger = DefaultLogger.getLogger(agentInfo.me());
        }
        logger.debug("调用MessageCoordinator");
        ArrayList<CommunicationMessage> policeMessages = new ArrayList<>();
        ArrayList<CommunicationMessage> ambulanceMessages = new ArrayList<>();
        ArrayList<CommunicationMessage> fireBrigadeMessages = new ArrayList<>();

        ArrayList<CommunicationMessage> voiceMessages = new ArrayList<>();

        StandardEntityURN agentType = getAgentType(agentInfo, worldInfo);

        for (CommunicationMessage msg : sendMessageList) {
            if (msg instanceof StandardMessage && !msg.isRadio()) {
                voiceMessages.add(msg);
            } else {
                if (msg instanceof MessageBuilding) {
                    fireBrigadeMessages.add(msg);
                } else if (msg instanceof MessageCivilian) {
                    if (((MessageCivilian) msg).getSendingPriority() != StandardMessagePriority.LOW){
                        ambulanceMessages.add(msg);
                        fireBrigadeMessages.add(msg);
                        policeMessages.add(msg);
                    }
                } else if (msg instanceof MessageRoad) {
                    if (((MessageRoad) msg).getSendingPriority() != StandardMessagePriority.LOW){
                        fireBrigadeMessages.add(msg);
                        ambulanceMessages.add(msg);
                        policeMessages.add(msg);
                    }

                } else if (msg instanceof CommandAmbulance) {
                    ambulanceMessages.add(msg);
                } else if (msg instanceof CommandFire) {
                    fireBrigadeMessages.add(msg);
                } else if (msg instanceof CommandPolice) {
                    ambulanceMessages.add(msg);
                    fireBrigadeMessages.add(msg);
                    policeMessages.add(msg);
                } else if (msg instanceof CommandScout) {
                    if (agentType == StandardEntityURN.FIRE_STATION) {
                        fireBrigadeMessages.add(msg);
                    } else if (agentType == StandardEntityURN.POLICE_OFFICE) {
                        policeMessages.add(msg);
                    } else if (agentType == StandardEntityURN.AMBULANCE_CENTRE) {
                        ambulanceMessages.add(msg);
                    }
                } else if (msg instanceof MessageReport) {
                    if (agentType == StandardEntityURN.FIRE_BRIGADE) {
                        fireBrigadeMessages.add(msg);
                    } else if (agentType == StandardEntityURN.POLICE_FORCE) {
                        policeMessages.add(msg);
                    } else if (agentType == StandardEntityURN.AMBULANCE_TEAM) {
                        ambulanceMessages.add(msg);
                    }
                } else if (msg instanceof MessageFireBrigade) {
                    fireBrigadeMessages.add(msg);
                    ambulanceMessages.add(msg);
                    policeMessages.add(msg);
                } else if (msg instanceof MessagePoliceForce) {
                    ambulanceMessages.add(msg);
                    policeMessages.add(msg);
                    fireBrigadeMessages.add(msg);
                } else if (msg instanceof MessageAmbulanceTeam) {
                    ambulanceMessages.add(msg);
                    policeMessages.add(msg);
                    fireBrigadeMessages.add(msg);
                }
            }
        }
        //channelSendMessageList.get(1).add(new CommandPolice(true, StandardMessagePriority.HIGH, null, agentInfo.getPosition(), CommandPolice.ACTION_CLEAR));
        if (scenarioInfo.getCommsChannelsCount() > 1) {
            int[] channelSize = new int[scenarioInfo.getCommsChannelsCount() - 1];

            List<StandardEntityURN> priority = this.seuChannelSubscriber.getPriority(scenarioInfo);
            if (!(allowedToSendRadioMessage(worldInfo, agentInfo, scenarioInfo))) {
                fireBrigadeMessages.removeIf(e -> !(e instanceof CommandPolice || e instanceof CommandFire || e instanceof CommandAmbulance || e instanceof MessageFireBrigade));
                policeMessages.removeIf(e -> !(e instanceof CommandPolice || e instanceof CommandFire || e instanceof CommandAmbulance));
                ambulanceMessages.removeIf(e -> !(e instanceof CommandPolice || e instanceof CommandFire || e instanceof CommandAmbulance || e instanceof MessageFireBrigade || e instanceof MessageAmbulanceTeam));
            }
            for (StandardEntityURN urn : priority) {
                if (urn == StandardEntityURN.FIRE_BRIGADE || urn == StandardEntityURN.FIRE_STATION) {
                    setSendMessages(scenarioInfo, StandardEntityURN.FIRE_BRIGADE, agentInfo, worldInfo, fireBrigadeMessages,
                            channelSendMessageList, channelSize);
                } else if (urn == StandardEntityURN.POLICE_FORCE || urn == StandardEntityURN.POLICE_OFFICE) {
                    setSendMessages(scenarioInfo, StandardEntityURN.POLICE_FORCE, agentInfo, worldInfo, policeMessages,
                            channelSendMessageList, channelSize);
                } else if (urn == StandardEntityURN.AMBULANCE_TEAM || urn == StandardEntityURN.AMBULANCE_CENTRE) {
                    setSendMessages(scenarioInfo, StandardEntityURN.AMBULANCE_TEAM, agentInfo, worldInfo, ambulanceMessages,
                            channelSendMessageList, channelSize);
                }
            }
        }

        ArrayList<StandardMessage> voiceMessageLowList = new ArrayList<>();
        ArrayList<StandardMessage> voiceMessageNormalList = new ArrayList<>();
        ArrayList<StandardMessage> voiceMessageHighList = new ArrayList<>();

        for (CommunicationMessage msg : voiceMessages) {
            if (msg instanceof StandardMessage m) {
                switch (m.getSendingPriority()) {
                    case LOW:
                        voiceMessageLowList.add(m);
                        break;
                    case NORMAL:
                        voiceMessageNormalList.add(m);
                        break;
                    case HIGH:
                        voiceMessageHighList.add(m);
                        break;
                }
            }
        }

        channelSendMessageList.get(0).addAll(voiceMessageHighList);
        channelSendMessageList.get(0).addAll(voiceMessageNormalList);
        channelSendMessageList.get(0).addAll(voiceMessageLowList);
    }

    protected int[] getChannelsByAgentType(StandardEntityURN agentType, AgentInfo agentInfo,
                                           WorldInfo worldInfo, ScenarioInfo scenarioInfo, int channelIndex) {
        int numChannels = scenarioInfo.getCommsChannelsCount()-1;
        int maxChannelCount = 0;
        boolean isPlatoon = isPlatoonAgent(agentInfo, worldInfo);
        if (isPlatoon) {
            maxChannelCount = scenarioInfo.getCommsChannelsMaxPlatoon();
        } else {
            maxChannelCount = scenarioInfo.getCommsChannelsMaxOffice();
        }
        int[] channels = new int[maxChannelCount];

        for (int i = 0; i < maxChannelCount; i++) {
            channels[i] = seuChannelSubscriber.getChannelNumber(agentType, i, numChannels, agentInfo, worldInfo, scenarioInfo);
        }
        return channels;
    }

    protected boolean isPlatoonAgent(AgentInfo agentInfo, WorldInfo worldInfo) {
        StandardEntityURN agentType = getAgentType(agentInfo, worldInfo);
        return agentType == StandardEntityURN.FIRE_BRIGADE ||
                agentType == StandardEntityURN.POLICE_FORCE ||
                agentType == StandardEntityURN.AMBULANCE_TEAM;
    }

    protected StandardEntityURN getAgentType(AgentInfo agentInfo, WorldInfo worldInfo) {
        return Objects.requireNonNull(worldInfo.getEntity(agentInfo.getID())).getStandardURN();
    }

    protected void setSendMessages(ScenarioInfo scenarioInfo, StandardEntityURN agentType, AgentInfo agentInfo,
                                   WorldInfo worldInfo, List<CommunicationMessage> messages,
                                   List<List<CommunicationMessage>> channelSendMessageList,
                                   int[] channelSize) {
        int channelIndex = 0;
        int[] channels = getChannelsByAgentType(agentType, agentInfo, worldInfo, scenarioInfo, channelIndex);
        int channel = channels[channelIndex];
        int channelCapacity = scenarioInfo.getCommsChannelBandwidth(channel);
        int allocatedCapacity = (int) (channelCapacity /
                (seuChannelSubscriber.getScenarioAgents(scenarioInfo) * seuChannelSubscriber.getSendMessageAgentsRatio()));
        switch (agentType) {
            case FIRE_BRIGADE:
            case FIRE_STATION:
                messages.sort(new FBMessageComparator());
                break;
            case POLICE_FORCE:
            case POLICE_OFFICE:
                messages.sort(new PFMessageComparator());
                break;
            case AMBULANCE_TEAM:
            case AMBULANCE_CENTRE:
                messages.sort(new ATMessageComparator());
                break;
        }
        for (int i = StandardMessagePriority.values().length - 1; i >= 0; i--) {
            for (CommunicationMessage msg : messages) {
                StandardMessage smsg = (StandardMessage) msg;
                int byteSize = smsg.getByteArraySize() / 8;
                if (smsg.getSendingPriority() == StandardMessagePriority.values()[i]) {
                    while (channelIndex < channels.length) {
                        channelSize[channel - 1] += byteSize;
                        if (channelSize[channel - 1] > allocatedCapacity) {
                            channelSize[channel - 1] -= byteSize;
                            channelIndex++;
                            if (channelIndex < channels.length) {
                                channel = channels[channelIndex];
                                channelCapacity = scenarioInfo.getCommsChannelBandwidth(channel);
                                allocatedCapacity = (int) (channelCapacity /
                                        (seuChannelSubscriber.getScenarioAgents(scenarioInfo) * seuChannelSubscriber.getSendMessageAgentsRatio()));
                            }
                        } else if (!channelSendMessageList.get(channel).contains(smsg)) {
                            channelSendMessageList.get(channel).add(smsg);
                            break;
                        }
                    }

                }
            }
        }
    }
    private boolean allowedToSendRadioMessage(WorldInfo worldInfo, AgentInfo agentInfo, ScenarioInfo scenarioInfo) {
        if (seuChannelSubscriber.isBandWidthSufficient(scenarioInfo)) {
            return true;
        }
        EntityID allowedToSendID = new EntityID(Integer.MAX_VALUE);
        Collection<StandardEntity> objectIDsInRange = worldInfo.getObjectsInRange(agentInfo.getID(), scenarioInfo.getPerceptionLosMaxDistance());
        for (StandardEntity entity : objectIDsInRange) {
            if (entity instanceof Human && !(entity instanceof Civilian)) {
                boolean notBuried = ((Human) entity).isBuriednessDefined() && ((Human) entity).getBuriedness() <= 0;
                boolean inDistanceThreshold = worldInfo.getDistance(agentInfo.getID(), entity.getID()) < ALLOWED_TO_SEND_DISTANCE_THRESHOLD;
                boolean inSameAreaAndSeenRange = agentInfo.getPosition().equals(Objects.requireNonNull(worldInfo.getPosition(entity.getID())).getID()) &&
                        worldInfo.getDistance(agentInfo.getID(), entity.getID()) < scenarioInfo.getPerceptionLosMaxDistance() * 0.5;


                if (notBuried && (inDistanceThreshold || inSameAreaAndSeenRange)) {
                    if (entity.getID().getValue() < allowedToSendID.getValue()) {
                        allowedToSendID = entity.getID();
                    }
                }
            }
        }
        return allowedToSendID.equals(agentInfo.getID());
    }

    public static class FBMessageComparator implements Comparator<CommunicationMessage> {
        @Override
        public int compare(CommunicationMessage t0, CommunicationMessage t1) {
            if (t0 instanceof MessageCivilian && !(t1 instanceof MessageCivilian)) {
                return -1;
            } else if (!(t0 instanceof MessageCivilian) && t1 instanceof MessageCivilian) {
                return 1;
            }else {
                return 0;
            }
        }
    }

    public static class ATMessageComparator implements Comparator<CommunicationMessage> {
        @Override
        public int compare(CommunicationMessage t0, CommunicationMessage t1) {
            if (t0 instanceof MessageCivilian && !(t1 instanceof MessageCivilian)) {
                return -1;
            } else if (!(t0 instanceof MessageCivilian) && t1 instanceof MessageCivilian) {
                return 1;
            }else {
                return 0;
            }
        }
    }

    public static class PFMessageComparator implements Comparator<CommunicationMessage> {
        @Override
        public int compare(CommunicationMessage t0, CommunicationMessage t1) {
            if (t0 instanceof CommandPolice && !(t1 instanceof CommandPolice)) {
                return -1;
            } else if (!(t0 instanceof CommandPolice) && t1 instanceof CommandPolice) {
                return 1;
            }else {
                return 0;
            }
        }
    }

}
