package SEU.module.comm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.config.ModuleConfig;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.platoon.PlatoonFire;
import adf.core.component.communication.ChannelSubscriber;
import adf.impl.tactics.DefaultTacticsFireBrigade;
import rescuecore2.config.Config;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.entities.StandardWorldModel;
import rescuecore2.worldmodel.EntityID;
import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;
import java.util.*;
import SEU.world.SEUDEBUG;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class SEUChannelSubscriber extends ChannelSubscriber {

    private static final double FB_RATIO = 1.5;
    private static final double PF_RATIO = 1.2;
    private static final double AT_RATIO = 2.0;
    private double sendMessageAgentsRatio = 0; //使用信息交互智能体的比例
    private Logger logger;

    @Override
    public void subscribe(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
                          MessageManager messageManager) { //用于重载
        if (sendMessageAgentsRatio == 0) {
            initSendMessageAgentsRatio(worldInfo, scenarioInfo); //（对每个智能体）初始化为视野面积*视野智能体数/地图总尺寸
            logger = DefaultLogger.getLogger(agentInfo.me());
        }
        logger.debug("进入SEUChannelSubcriber");
        if (agentInfo.getTime() == scenarioInfo.getKernelAgentsIgnoreuntil()) { //地图config，时间为3,即3T智能体开始运行
            int numChannels = scenarioInfo.getCommsChannelsCount() - 1; //channel数量，在地图config中定义

            int maxChannelCount;
            boolean isPlatoon = isPlatoonAgent(agentInfo, worldInfo); //判断agent是否为智能体
            if (isPlatoon) {
                maxChannelCount = scenarioInfo.getCommsChannelsMaxPlatoon(); //获取使用的channel最大数量，2左右
            } else {
                maxChannelCount = scenarioInfo.getCommsChannelsMaxOffice(); //2左右
            }

            StandardEntityURN agentType = getAgentType(agentInfo, worldInfo); //worldinfo下agent类型
            int[] channels = new int[maxChannelCount];
            for (int i = 0; i < maxChannelCount; i++) {
                channels[i] = getChannelNumber(agentType, i, numChannels, agentInfo, worldInfo, scenarioInfo);
            }
            messageManager.subscribeToChannels(channels);
            
            // 如果调试模式开启，打印智能体的订阅信息
        if (SEUDEBUG.DEBUG_CHANNEL_SUBSCRIBE) {
            // 输出智能体类型、ID、时间以及订阅的频道
            System.out.println("Time: " + agentInfo.getTime() + 
                               ", Agent Type: " + agentType + 
                               ", Agent ID: " + agentInfo.getID().getValue() + 
                               ", Subscribed Channels: " + Arrays.toString(channels));
            // 日志记录
            logger.debug("进入调试");
            logger.debug("Subscribed Channels:"+Arrays.toString(channels));
        }
        }
        
    }

    //判断在当前worldinfo下agentinfo是否为智能体
    protected static boolean isPlatoonAgent(AgentInfo agentInfo, WorldInfo worldInfo) {
        StandardEntityURN agentType = getAgentType(agentInfo, worldInfo);
        return agentType == FIRE_BRIGADE ||
                agentType == StandardEntityURN.POLICE_FORCE ||
                agentType == StandardEntityURN.AMBULANCE_TEAM;
    }

    //判断agenttype
    protected static StandardEntityURN getAgentType(AgentInfo agentInfo, WorldInfo worldInfo) {
        return Objects.requireNonNull(worldInfo.getEntity(agentInfo.getID())).getStandardURN();
    }

    public int getChannelNumber(StandardEntityURN agentType, int channelIndex, int numChannels, AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo) {
        int[] channels = getChannels(agentType, numChannels, agentInfo, worldInfo, scenarioInfo);
        return channels[channelIndex];
    }

    private int[] getChannels(StandardEntityURN agentType, int numChannels, AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo) {
        if (numChannels < 1) { //没有信道
            return new int[1];
        }
        int scenarioAgents = getScenarioAgents(scenarioInfo); //场景智能体数量
        double fbRequiredBandwidth = 40 * scenarioAgents * getSendMessageAgentsRatio(); //通过智能体数量确定带宽
        double pfRequiredBandwidth = 44 * scenarioAgents * getSendMessageAgentsRatio();
        double atRequiredBandwidth = 44 * scenarioAgents * getSendMessageAgentsRatio();
        double[] requiredBandWidthRemain = new double[3]; //根据智能体数量计算得到需要的带宽
        requiredBandWidthRemain[0] = fbRequiredBandwidth;
        requiredBandWidthRemain[1] = pfRequiredBandwidth;
        requiredBandWidthRemain[2] = atRequiredBandwidth;
        int maxChannels;

        if (isPlatoonAgent(agentInfo, worldInfo)) {
            maxChannels = scenarioInfo.getCommsChannelsMaxPlatoon();
        } else {
            maxChannels = scenarioInfo.getCommsChannelsMaxOffice();
        }

        int[] fbChannels = new int[maxChannels];
        int[] pfChannels = new int[maxChannels];
        int[] atChannels = new int[maxChannels];

        Map<Integer, Integer> radioBandWidthRemainMap = getRadioBandWidthMap(numChannels, scenarioInfo); //实际有的带宽
        ArrayList<Map.Entry<Integer, Integer>> sortedRadioBandWidthRemain = new ArrayList<>(radioBandWidthRemainMap.entrySet());
        sortedRadioBandWidthRemain.sort((t0, t1) -> t1.getValue() - t0.getValue()); //对带宽进行排序
        List<StandardEntityURN> priority = getPriority(scenarioInfo); //对优先级进行排序，返回权重结果
        for (StandardEntityURN urn : priority) {
            for (int i = 0; i < maxChannels; i++) {
                Map.Entry<Integer, Integer> radioBandWidthRemain = sortedRadioBandWidthRemain.get(0);
                Integer BandWidthRemainValue = radioBandWidthRemain.getValue();
                if (urn == FIRE_BRIGADE || urn == StandardEntityURN.FIRE_STATION) {
                    double tmp = requiredBandWidthRemain[0];
                    if (BandWidthRemainValue > requiredBandWidthRemain[0]) {
                        requiredBandWidthRemain[0] = 0;
                        radioBandWidthRemain.setValue((int) (BandWidthRemainValue - tmp));
                        if(SEUDEBUG.DEBUG_CHANNEL_SUBSCRIBE){
                            // 实时输出频道容量和占用情况
                            System.out.println("Channel " + radioBandWidthRemain.getKey() + 
                            ": Capacity = " + BandWidthRemainValue + 
                            ", Remaining = " + (BandWidthRemainValue - tmp));
                        }
                    } else if (i == maxChannels - 1) {
                        requiredBandWidthRemain[0] = 0;
                        radioBandWidthRemain.setValue((int) (BandWidthRemainValue - tmp));
                    } else {
                        if (BandWidthRemainValue < 0) {
                            requiredBandWidthRemain[0] = 0;
                            radioBandWidthRemain.setValue((int) (BandWidthRemainValue - tmp));
                        } else {
                            requiredBandWidthRemain[0] = tmp - radioBandWidthRemain.getValue();
                            radioBandWidthRemain.setValue(0);
                        }
                    }
                    fbChannels[i] = radioBandWidthRemain.getKey();
                } else if (urn == StandardEntityURN.POLICE_FORCE || urn == POLICE_OFFICE) {
                    double tmp = requiredBandWidthRemain[1];
                    printChannelStatus(radioBandWidthRemainMap); // 输出当前频道状态
                    if (BandWidthRemainValue > requiredBandWidthRemain[1]) {
                        requiredBandWidthRemain[1] = 0;
                        radioBandWidthRemain.setValue((int) (BandWidthRemainValue - tmp));
                    } else if (i == maxChannels - 1) {
                        requiredBandWidthRemain[1] = 0;
                        radioBandWidthRemain.setValue((int) (BandWidthRemainValue - tmp));
                    } else {
                        if (BandWidthRemainValue < 0) {
                            requiredBandWidthRemain[1] = 0;
                            radioBandWidthRemain.setValue((int) (BandWidthRemainValue - tmp));
                        } else {
                            requiredBandWidthRemain[1] = tmp - radioBandWidthRemain.getValue();
                            radioBandWidthRemain.setValue(0);
                        }
                    }
                    pfChannels[i] = radioBandWidthRemain.getKey();
                } else if (urn == StandardEntityURN.AMBULANCE_TEAM || urn == AMBULANCE_CENTRE) {
                    double tmp = requiredBandWidthRemain[2];
                    if (BandWidthRemainValue > requiredBandWidthRemain[2]) {
                        requiredBandWidthRemain[2] = 0;
                        radioBandWidthRemain.setValue((int) (BandWidthRemainValue - tmp));
                    } else if (i == maxChannels - 1) {
                        requiredBandWidthRemain[2] = 0;
                        radioBandWidthRemain.setValue((int) (BandWidthRemainValue - tmp));
                    } else {
                        if (BandWidthRemainValue < 0) {
                            requiredBandWidthRemain[2] = 0;
                            radioBandWidthRemain.setValue((int) (BandWidthRemainValue - tmp));
                        } else {
                            requiredBandWidthRemain[2] = tmp - radioBandWidthRemain.getValue();
                            radioBandWidthRemain.setValue(0);
                        }
                    }
                    atChannels[i] = radioBandWidthRemain.getKey();
                }
                sortedRadioBandWidthRemain.sort((t0, t1) -> t1.getValue() - t0.getValue());
            }
        }
        if (agentType == FIRE_BRIGADE || agentType == StandardEntityURN.FIRE_STATION) {
            return fbChannels;
        } else if (agentType == StandardEntityURN.POLICE_FORCE || agentType == POLICE_OFFICE) {
            return pfChannels;
        } else if (agentType == StandardEntityURN.AMBULANCE_TEAM || agentType == AMBULANCE_CENTRE) {
            return atChannels;
        }
        return new int[1];
    }

    public static int getScenarioAgents(ScenarioInfo scenarioInfo) {
        return scenarioInfo.getScenarioAgentsFb() + scenarioInfo.getScenarioAgentsFs() + scenarioInfo.getScenarioAgentsPf() +
                scenarioInfo.getScenarioAgentsPo() + scenarioInfo.getScenarioAgentsAt() + scenarioInfo.getScenarioAgentsAc();
    }

    //返回通道对应带宽的map
    private static Map<Integer, Integer> getRadioBandWidthMap(int numChannels, ScenarioInfo scenarioInfo) {
        HashMap<Integer, Integer> radioBandWidthMap = new HashMap<>();
        for (int i = 1; i <= numChannels; i++) {
            radioBandWidthMap.put(i, scenarioInfo.getCommsChannelBandwidth(i));
        }
        return radioBandWidthMap;
    }

    public void initSendMessageAgentsRatio(WorldInfo worldInfo, ScenarioInfo scenarioInfo) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        Pair<Integer, Integer> pos;
        for (StandardEntity standardEntity : worldInfo.getAllEntities()) {
            pos = worldInfo.getLocation(standardEntity);
            if (pos != null && pos.first() < minX) minX = pos.first();
            if (pos != null && pos.second() < minY) minY = pos.second();
            if (pos != null && pos.first() > maxX) maxX = pos.first();
            if (pos != null && pos.second() > maxY) maxY = pos.second();
        }
        double mapSize = ((maxX - minX) / 1000.0) * ((maxY - minY) / 1000.0);
        double agentCoverageSize = (scenarioInfo.getPerceptionLosMaxDistance() / 1000.0)
                * (scenarioInfo.getPerceptionLosMaxDistance() / 1000.0)
                * Math.PI
                * getScenarioAgents(scenarioInfo);
        sendMessageAgentsRatio = Math.min(1, Math.sqrt(mapSize / agentCoverageSize));
    }

    public static boolean isBandWidthSufficient(ScenarioInfo scenarioInfo) {
        double fbRequiredBandwidth = 40 * getScenarioAgents(scenarioInfo);
        double pfRequiredBandwidth = 44 * getScenarioAgents(scenarioInfo);
        double atRequiredBandwidth = 44 * getScenarioAgents(scenarioInfo);
        int totalDistributableRadioBandWith = getTotalDistributableRadioBandWith(scenarioInfo);
        return totalDistributableRadioBandWith > fbRequiredBandwidth + pfRequiredBandwidth + atRequiredBandwidth;
    }

    private static int getTotalDistributableRadioBandWith(ScenarioInfo scenarioInfo) {
        Map<Integer, Integer> radioBandWidthRemainMap = getRadioBandWidthMap(scenarioInfo.getCommsChannelsCount() - 1, scenarioInfo);
        ArrayList<Map.Entry<Integer, Integer>> sortedRadioBandWidthRemain = new ArrayList<>(radioBandWidthRemainMap.entrySet());
        int commsChannelsMaxPlatoon = scenarioInfo.getCommsChannelsMaxPlatoon();
        int result = 0;
        for (int i = 0; i < sortedRadioBandWidthRemain.size() && i < commsChannelsMaxPlatoon * 3; i++) {
            result += sortedRadioBandWidthRemain.get(i).getValue();
        }
        return result;
    }

    public double getSendMessageAgentsRatio() {
        return this.sendMessageAgentsRatio;
    }

    public static List<StandardEntityURN> getPriority(ScenarioInfo scenarioInfo) {
        double fb = (scenarioInfo.getScenarioAgentsFb() + scenarioInfo.getScenarioAgentsFs()) * FB_RATIO;
        double pf = (scenarioInfo.getScenarioAgentsPf() + scenarioInfo.getScenarioAgentsPo()) * PF_RATIO;
        double at = (scenarioInfo.getScenarioAgentsAt() + scenarioInfo.getScenarioAgentsAc()) * AT_RATIO;
        ArrayList<StandardEntityURN> result = new ArrayList<>();
        HashMap<StandardEntityURN, Double> map = new HashMap<>();
        map.put(FIRE_BRIGADE, fb);
        map.put(StandardEntityURN.POLICE_FORCE, pf);
        map.put(StandardEntityURN.AMBULANCE_TEAM, at);
        ArrayList<Map.Entry<StandardEntityURN, Double>> entryArrayList = new ArrayList<>(map.entrySet());
        entryArrayList.sort((t0, t1) -> (int) (t1.getValue() - t0.getValue()));
        for (Map.Entry<StandardEntityURN, Double> entry : entryArrayList) {
            result.add(entry.getKey());
        }
        return result;
    }

    private void printChannelStatus(Map<Integer, Integer> radioBandWidthRemainMap) {
        for (Map.Entry<Integer, Integer> entry : radioBandWidthRemainMap.entrySet()) {
            System.out.println("Channel " + entry.getKey() + 
                               ": Capacity = " + entry.getValue());
        }
    }

    public static void main(String[] args) {
        //用于测试和验证频道分配逻辑的功能
        //使用 Config 对象配置一个模拟场景。
        Config config = new Config();
        //设置场景中各类智能体（消防队、警察、救护队等）的数量
        config.setIntValue("scenario.agents.fb", 30);
        config.setIntValue("scenario.agents.at", 2);
        config.setIntValue("scenario.agents.pf", 30);
        config.setIntValue("scenario.agents.fs", 2);
        config.setIntValue("scenario.agents.ac", 30);
        config.setIntValue("scenario.agents.po", 2);
        //义通信频道的数量（comms.channels.count=7）及其各自的带宽（如 256, 64, 等）
        config.setIntValue("comms.channels." + "0" + ".bandwidth", 256);
        config.setIntValue("comms.channels." + "1" + ".bandwidth", 64);
        config.setIntValue("comms.channels." + "2" + ".bandwidth", 32);
        config.setIntValue("comms.channels." + "3" + ".bandwidth", 128);
        config.setIntValue("comms.channels." + "4" + ".bandwidth", 4000);
        config.setIntValue("comms.channels." + "5" + ".bandwidth", 4000);
        config.setIntValue("comms.channels." + "6" + ".bandwidth", 4000);
        config.setIntValue("comms.channels.count", 7);
        //设置每个小队智能体最多使用的频道数（comms.channels.max.platoon=2）
        config.setIntValue("comms.channels.max.platoon", 2);
        //创建一个模拟的消防队实体（FireBrigade），并将其添加到世界模型（worldModel）中
        FireBrigade fireBrigade = new FireBrigade(new EntityID(1111111));
        StandardWorldModel worldModel = new StandardWorldModel();
        PlatoonFire platoonFire = new PlatoonFire(new DefaultTacticsFireBrigade(),"SEU" ,false, false,
                new ModuleConfig("./config/module.cfg", new ArrayList<>()),
                new DevelopData(false, "./data/develop.json", new ArrayList<>()));
        //初始化 ScenarioInfo 和 WorldInfo，用于描述场景和世界的当前状态
        ScenarioInfo scenarioInfo = new ScenarioInfo(config, ScenarioInfo.Mode.NON_PRECOMPUTE);
        WorldInfo worldInfo = new WorldInfo(worldModel);
        worldInfo.addEntity(fireBrigade);
        //创建一个智能体信息对象（AgentInfo），用于表示当前智能体的状态。
        AgentInfo agentInfo = new AgentInfo(platoonFire, worldModel);
        //调用 getChannelNumber 方法，测试不同类型的智能体（消防队、警察、救护队）在给定场景下的频道分配结果。
        int numChannels = scenarioInfo.getCommsChannelsCount() - 1;
        int maxChannels = scenarioInfo.getCommsChannelsMaxPlatoon();
        SEUChannelSubscriber subscriber = new SEUChannelSubscriber();
        for (int i = 0; i < maxChannels; i++) {
            System.out.println("FIREBRIGADE-" + i + ":" + subscriber.getChannelNumber(FIRE_BRIGADE, i, numChannels, agentInfo, worldInfo, scenarioInfo));
        }
        for (int i = 0; i < maxChannels; i++) {
            System.out.println("POLICE-" + i + ":" + subscriber.getChannelNumber(POLICE_OFFICE, i, numChannels, agentInfo, worldInfo, scenarioInfo));
        }
        for (int i = 0; i < maxChannels; i++) {
            System.out.println("AMB-" + i + ":" + subscriber.getChannelNumber(AMBULANCE_CENTRE, i, numChannels, agentInfo, worldInfo, scenarioInfo));
        }
    }
}