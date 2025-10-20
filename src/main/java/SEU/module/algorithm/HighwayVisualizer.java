package SEU.module.algorithm;

import rescuecore2.standard.entities.*;
import rescuecore2.misc.gui.ScreenTransform;
import rescuecore2.misc.gui.ShapeDebugFrame;
import rescuecore2.worldmodel.EntityID; 

import javax.swing.*;
import java.awt.*;
import java.awt.event.*; 
import java.util.*;
import java.util.List;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import java.text.SimpleDateFormat; 

/**
 * 高速公路可视化器实现 - 继承自StaticClusteringVisualizer
 */
public class HighwayVisualizer extends StaticClusteringVisualizer {
    
    private final SEUHighways clustering;
    
    public HighwayVisualizer(SEUHighways clustering, List<StandardEntity> allEntities) {
        // 先调用父类构造函数，但传入null作为clustering参数
        super(null, allEntities);
        
        // 在调用setupUI之前先设置clustering字段
        this.clustering = clustering;
        
        // 添加null检查
        if (clustering == null) {
            throw new IllegalArgumentException("clustering参数不能为null");
        }
        
        // 初始化父类的clustering字段
        try {
            java.lang.reflect.Field clusteringField = StaticClusteringVisualizer.class.getDeclaredField("clustering");
            clusteringField.setAccessible(true);
            clusteringField.set(this, clustering);
        } catch (Exception e) {
            System.err.println("无法设置父类clustering字段: " + e.getMessage());
        }
        
        // 设置高速公路特定的颜色映射
        initializeHighwayColors();
        
        // 设置UI，确保使用正确的聚类数据
        reinitializeUI();
    }
    
    /**
     * 初始化UI，确保聚类数据正确设置
     */
    private void reinitializeUI() {
        // 计算边界
        calculateBounds();
        
        // 初始化聚类颜色
        initializeClusterColors();
        
        // 设置正确的窗口标题
        setCorrectTitle();
    }
    
    /**
     * 设置正确的窗口标题
     */
    private void setCorrectTitle() {
        if (clustering != null) {
            setTitle("高速公路网络可视化 - SEUHighways - 聚类: " + clustering.getClusterNumber() + 
                    ", 高速公路实体: " + clustering.getClusterEntityIDs(0).size());
        } else {
            setTitle("高速公路网络可视化 - 聚类数据未初始化");
        }
    }
    
    /**
     * setupUI方法
     */
    @Override
    protected void setupUI() {
        // 先设置基本的UI属性，不访问clustering字段
        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setBackground(Color.BLACK);

        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent we) {
                dispose();
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showEntityInfo(e.getX(), e.getY());
            }
        });
        
        // 标题在reinitializeUI中设置
    }
    
    /**
     * 初始化聚类颜色方法
     */
    @Override
    protected void initializeClusterColors() {
        if (clustering == null) {
            System.err.println("警告: clustering为null，无法初始化聚类颜色");
            return;
        }
        
        int clusterCount = clustering.getClusterNumber();
        clusterColors = new Color[clusterCount];
        for (int i = 0; i < clusterCount; i++) {
            clusterColors[i] = Color.RED; // 所有高速公路聚类都使用红色
        }
    }
    
    /**
     * 初始化颜色方法
     */
    @Override
    protected void initializeColors() {
        entityColors = new HashMap<>();
        entityColors.put(StandardEntityURN.ROAD, Color.LIGHT_GRAY);
        entityColors.put(StandardEntityURN.HYDRANT, Color.CYAN);
        entityColors.put(StandardEntityURN.BUILDING, Color.ORANGE);
        entityColors.put(StandardEntityURN.GAS_STATION, Color.RED);
        entityColors.put(StandardEntityURN.REFUGE, Color.GREEN);
        entityColors.put(StandardEntityURN.POLICE_OFFICE, Color.BLUE);
        entityColors.put(StandardEntityURN.FIRE_STATION, Color.RED);
        entityColors.put(StandardEntityURN.AMBULANCE_CENTRE, Color.WHITE);
    }
    
    /**
     * 初始化高速公路特定的颜色映射
     */
    private void initializeHighwayColors() {
        // 高速公路使用红色，非高速公路使用浅灰色
        entityColors.clear();
        entityColors.put(StandardEntityURN.ROAD, Color.LIGHT_GRAY);
        entityColors.put(StandardEntityURN.HYDRANT, Color.CYAN);
        entityColors.put(StandardEntityURN.BUILDING, Color.ORANGE);
        entityColors.put(StandardEntityURN.GAS_STATION, Color.RED);
        entityColors.put(StandardEntityURN.REFUGE, Color.GREEN);
        entityColors.put(StandardEntityURN.POLICE_OFFICE, Color.BLUE);
        entityColors.put(StandardEntityURN.FIRE_STATION, Color.RED);
        entityColors.put(StandardEntityURN.AMBULANCE_CENTRE, Color.WHITE);
        
        // 设置聚类颜色：高速公路为红色
        if (clustering != null) {
            int clusterCount = clustering.getClusterNumber();
            clusterColors = new Color[clusterCount];
            for (int i = 0; i < clusterCount; i++) {
                clusterColors[i] = Color.RED; // 所有高速公路聚类都使用红色
            }
        }
    }
    
    /**
     * 绘制实体方法，突出显示高速公路
     */
    @Override
    protected void drawEntities(Graphics2D g2d) {
        if (clustering == null) {
            System.err.println("警告: clustering为null，无法绘制高速公路");
            super.drawEntities(g2d);
            return;
        }
        
        for (StandardEntity entity : allEntities) {
            if (entity instanceof Area) {
                Area area = (Area) entity;
                
                // 判断是否为高速公路
                boolean isHighway = clustering.getClusterIndex(entity) == 0;
                
                // 根据实体类型和是否为高速公路设置颜色
                StandardEntityURN urn = entity.getStandardURN();
                Color color = isHighway ? Color.RED : entityColors.getOrDefault(urn, Color.GRAY);
                
                // 绘制实体形状
                drawEntityShape(g2d, area, color, isHighway);
                
                // 绘制实体中心点
                Point screenPos = worldToScreen(area.getX(), area.getY());
                if (isHighway) {
                    // 高速公路用更大的红点突出显示
                    g2d.setColor(Color.RED);
                    g2d.fillOval(screenPos.x - 4, screenPos.y - 4, 8, 8);
                    g2d.setColor(Color.WHITE);
                    g2d.drawOval(screenPos.x - 4, screenPos.y - 4, 8, 8);
                } else {
                    // 普通实体用小灰点
                    g2d.setColor(color);
                    g2d.fillOval(screenPos.x - POINT_SIZE / 2, screenPos.y - POINT_SIZE / 2, POINT_SIZE, POINT_SIZE);
                    g2d.setColor(Color.WHITE);
                    g2d.drawOval(screenPos.x - POINT_SIZE / 2, screenPos.y - POINT_SIZE / 2, POINT_SIZE, POINT_SIZE);
                }
            }
        }
    }
    
    /**
     * 绘制实体形状方法，突出显示高速公路
     */
    private void drawEntityShape(Graphics2D g2d, Area area, Color color, boolean isHighway) {
        try {
            int[] apexArray = area.getApexList();
            if (apexArray == null || apexArray.length < 6) {
                return;
            }

            Polygon polygon = new Polygon();
            for (int i = 0; i < apexArray.length; i += 2) {
                if (i + 1 < apexArray.length) {
                    double worldX = apexArray[i];
                    double worldY = apexArray[i + 1];
                    Point screenPoint = worldToScreen(worldX, worldY);
                    polygon.addPoint(screenPoint.x, screenPoint.y);
                }
            }

            if (isHighway) {
                // 高速公路用半透明红色填充
                Color fillColor = new Color(255, 0, 0, 100);
                g2d.setColor(fillColor);
                g2d.fillPolygon(polygon);
                
                // 红色边框
                g2d.setColor(Color.RED);
                g2d.setStroke(new BasicStroke(2.0f)); // 更粗的边框
                g2d.drawPolygon(polygon);
            } else {
                // 普通实体用原色
                Color fillColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), 50);
                g2d.setColor(fillColor);
                g2d.fillPolygon(polygon);
                
                g2d.setColor(color);
                g2d.setStroke(new BasicStroke(1.0f));
                g2d.drawPolygon(polygon);
            }

        } catch (Exception e) {
            // 回退到绘制中心点
            System.err.println("绘制实体形状时出错: " + e.getMessage());
        }
    }
    
    /**
     * 绘制聚类方法，简化高速公路的可视化
     */
    @Override
    protected void drawClusters(Graphics2D g2d) {
        if (clustering == null) {
            System.err.println("警告: clustering为null，无法绘制聚类");
            return;
        }
        
        int clusterCount = clustering.getClusterNumber();

        for (int i = 0; i < clusterCount; i++) {
            Color clusterColor = clusterColors[i];

            // 计算聚类中心
            double centerX = 0, centerY = 0;
            int memberCount = 0;
            Collection<EntityID> members = clustering.getClusterEntityIDs(i);

            if (members != null) {
                for (EntityID memberId : members) {
                    StandardEntity member = findEntityById(memberId);
                    if (member instanceof Area) {
                        Area area = (Area) member;
                        centerX += area.getX();
                        centerY += area.getY();
                        memberCount++;
                    }
                }

                if (memberCount > 0) {
                    centerX /= memberCount;
                    centerY /= memberCount;

                    // 绘制聚类中心（黄色大方块）
                    Point centerScreen = worldToScreen(centerX, centerY);
                    g2d.setColor(Color.YELLOW);
                    g2d.fillRect(centerScreen.x - CENTER_SIZE / 2, centerScreen.y - CENTER_SIZE / 2, CENTER_SIZE, CENTER_SIZE);
                    g2d.setColor(Color.BLACK);
                    g2d.drawRect(centerScreen.x - CENTER_SIZE / 2, centerScreen.y - CENTER_SIZE / 2, CENTER_SIZE, CENTER_SIZE);
                    
                    // 在中心点显示聚类编号
                    g2d.setColor(Color.BLACK);
                    g2d.drawString("C" + i, centerScreen.x - 5, centerScreen.y + 5);
                }
            }
        }
    }
    
    /**
     * 显示高速公路特定的信息
     */
    @Override
    protected void drawLegend(Graphics2D g2d) {
        if (clustering == null) {
            System.err.println("警告: clustering为null，无法绘制图例");
            super.drawLegend(g2d);
            return;
        }
        
        int legendX = WINDOW_WIDTH - 250;
        int legendY = 50;
        int lineHeight = 20;

        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("宋体", Font.BOLD, 14));
        g2d.drawString("高速公路可视化图例:", legendX, legendY);

        int index = 1;
        
        // 高速公路图例
        g2d.setColor(Color.RED);
        g2d.fillRect(legendX, legendY + index * lineHeight, 15, 10);
        g2d.setColor(Color.WHITE);
        g2d.drawString("高速公路实体 (" + clustering.getClusterEntityIDs(0).size() + "个)", 
                       legendX + 20, legendY + index * lineHeight + 9);
        index++;
        
        // 聚类中心图例
        g2d.setColor(Color.YELLOW);
        g2d.fillRect(legendX, legendY + index * lineHeight, 15, 15);
        g2d.setColor(Color.WHITE);
        g2d.drawString("聚类中心", legendX + 20, legendY + index * lineHeight + 12);
        index++;
        
        // 实体类型图例
        g2d.setColor(Color.WHITE);
        g2d.drawString("实体类型:", legendX, legendY + index * lineHeight);
        index++;
        
        for (Map.Entry<StandardEntityURN, Color> entry : entityColors.entrySet()) {
            g2d.setColor(entry.getValue());
            g2d.fillRect(legendX, legendY + index * lineHeight, 15, 10);
            g2d.setColor(Color.WHITE);
            g2d.drawString(entry.getKey().toString(), legendX + 20, legendY + index * lineHeight + 9);
            index++;
        }
        
        // 统计信息
        g2d.setColor(Color.WHITE);
        g2d.drawString("统计信息:", legendX, legendY + index * lineHeight);
        index++;
        g2d.drawString("总实体数: " + allEntities.size(), legendX + 10, legendY + index * lineHeight);
        index++;
        g2d.drawString("采样点数: " + SEUHighways.SAMPLE_NUMBER, legendX + 10, legendY + index * lineHeight);
        index++;
        g2d.drawString("高速公路比例: " + 
                       String.format("%.1f%%", (clustering.getClusterEntityIDs(0).size() * 100.0 / allEntities.size())), 
                       legendX + 10, legendY + index * lineHeight);
    }
    
    /**
     * 显示实体信息方法，包含高速公路信息
     */
    @Override
    protected void showEntityInfo(int screenX, int screenY) {
        if (clustering == null) {
            System.err.println("警告: clustering为null，无法显示实体信息");
            super.showEntityInfo(screenX, screenY);
            return;
        }
        
        for (StandardEntity entity : allEntities) {
            if (entity instanceof Area) {
                Area area = (Area) entity;
                Point entityScreen = worldToScreen(area.getX(), area.getY());
                double distance = Math.hypot(entityScreen.x - screenX, entityScreen.y - screenY);

                if (distance <= POINT_SIZE * 2) { // 增大点击范围
                    int clusterIndex = clustering.getClusterIndex(entity);
                    String clusterInfo = (clusterIndex >= 0) 
                            ? "高速公路实体 (聚类 " + clusterIndex + ")" 
                            : "非高速公路实体";

                    System.out.println("实体ID: " + entity.getID() +
                            ", 类型: " + entity.getStandardURN() +
                            ", 坐标: (" + area.getX() + ", " + area.getY() + ")" +
                            ", " + clusterInfo);
                    break;
                }
            }
        }
    }
    
    /**
     * 高速公路特定的可视化启动方法
     */
    @Override
    public void visualize() {
        // 确保标题正确设置
        setCorrectTitle();
        
        setVisible(true);

        // 延迟保存截图
        try {
            Thread.sleep(2000);
            saveScreenshot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 保存截图方法，使用高速公路特定的文件名
     */
    @Override
    public void saveScreenshot() {
        try {
            BufferedImage image = new BufferedImage(WINDOW_WIDTH, WINDOW_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(Color.BLACK);
            g2d.fillRect(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
            paint(g2d);

            // 使用包含高速公路信息的文件名
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String timestamp = dateFormat.format(new Date());
            String fileName = "highways_" + timestamp + "_entities" + 
                            (clustering != null ? clustering.getClusterEntityIDs(0).size() : "unknown") + ".png";

            File outputDir = new File("visualization_output");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            File outputFile = new File(outputDir, fileName);
            ImageIO.write(image, "png", outputFile);

            System.out.println("高速公路可视化图像已保存至: " + outputFile.getAbsolutePath());
            g2d.dispose();
        } catch (Exception e) {
            System.err.println("保存高速公路可视化图像时出错: " + e.getMessage());
        }
    }
}