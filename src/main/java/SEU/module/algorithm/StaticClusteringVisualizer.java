package SEU.module.algorithm;

import java.awt.Color;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import adf.core.component.module.algorithm.StaticClustering;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

/**
 * 通用StaticClustering可视化工具
 * 可以可视化任何继承自StaticClustering的聚类算法
 * 修改为仿照KmeansVisualizer的画图方式
 */
public class StaticClusteringVisualizer extends Frame {

    // 聚类数据
    protected StaticClustering clustering;
    protected List<StandardEntity> allEntities;
    protected Map<StandardEntityURN, Color> entityColors;

    // 显示参数
    protected static final int WINDOW_WIDTH = 1200;
    protected static final int WINDOW_HEIGHT = 800;
    protected static final int MARGIN = 50;
    protected static final int POINT_SIZE = 1;
    protected static final int CENTER_SIZE = 12;

    // 坐标缩放
    protected double minX = Double.MAX_VALUE;
    protected double maxX = Double.MIN_VALUE;
    protected double minY = Double.MAX_VALUE;
    protected double maxY = Double.MIN_VALUE;

    // 聚类颜色
    protected Color[] clusterColors;

    /**
     * 构造函数
     *
     * @param clustering StaticClustering实例
     * @param entities 所有要可视化的实体
     */
    public StaticClusteringVisualizer(StaticClustering clustering, List<StandardEntity> entities) {
        this.clustering = clustering;
        this.allEntities = entities;
        initializeColors();
        calculateBounds();
        setupUI();
        initializeClusterColors();
    }

    /**
     * 初始化实体类型颜色映射
     */
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
        entityColors.put(StandardEntityURN.FIRE_BRIGADE, Color.RED);
        entityColors.put(StandardEntityURN.POLICE_FORCE, Color.BLUE);
        entityColors.put(StandardEntityURN.AMBULANCE_TEAM, Color.WHITE);
    }

    /**
     * 初始化聚类颜色
     */
    protected void initializeClusterColors() {
        int clusterCount = clustering.getClusterNumber();
        clusterColors = new Color[clusterCount];
        float hueStep = 1.0f / Math.max(1, clusterCount);

        for (int i = 0; i < clusterCount; i++) {
            clusterColors[i] = Color.getHSBColor(i * hueStep, 0.8f, 0.9f);
        }
    }

    /**
     * 计算坐标边界用于缩放
     */
    protected void calculateBounds() {
        for (StandardEntity entity : allEntities) {
            if (entity instanceof Area) {
                Area area = (Area) entity;
                minX = Math.min(minX, area.getX());
                maxX = Math.max(maxX, area.getX());
                minY = Math.min(minY, area.getY());
                maxY = Math.max(maxY, area.getY());
            }
        }

        // 确保边界有效
        if (minX == maxX) {
            minX -= 100;
            maxX += 100;
        }
        if (minY == maxY) {
            minY -= 100;
            maxY += 100;
        }
    }

    /**
     * 设置UI界面
     */
    protected void setupUI() {
        setTitle("Static Clustering Visualization - " + clustering.getClass().getSimpleName());
        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setBackground(Color.BLACK);

        // 添加窗口关闭事件
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent we) {
                dispose();
            }
        });

        // 添加鼠标点击事件来显示详细信息
        addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                showEntityInfo(e.getX(), e.getY());
            }
        });
    }

    /**
     * 将世界坐标转换为屏幕坐标
     */
    protected Point worldToScreen(double worldX, double worldY) {
        int screenX = MARGIN + (int) ((worldX - minX) * (WINDOW_WIDTH - 2 * MARGIN) / (maxX - minX));
        int screenY = WINDOW_HEIGHT - MARGIN - (int) ((worldY - minY) * (WINDOW_HEIGHT - 2 * MARGIN) / (maxY - minY));
        return new Point(screenX, screenY);
    }

    /**
     * 绘制方法
     */
    @Override
    public void paint(Graphics g) {
        super.paint(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawGrid(g2d);
        drawEntities(g2d);
        drawClusters(g2d);
        drawLegend(g2d);
    }

    /**
     * 绘制网格
     */
    protected void drawGrid(Graphics2D g2d) {
        g2d.setColor(Color.DARK_GRAY);
        g2d.drawRect(MARGIN, MARGIN, WINDOW_WIDTH - 2 * MARGIN, WINDOW_HEIGHT - 2 * MARGIN);

        // 绘制坐标轴标签
        g2d.setColor(Color.WHITE);
        g2d.drawString(String.format("X: %.1f", minX), MARGIN, WINDOW_HEIGHT - MARGIN + 20);
        g2d.drawString(String.format("X: %.1f", maxX), WINDOW_WIDTH - MARGIN - 40, WINDOW_HEIGHT - MARGIN + 20);
        g2d.drawString(String.format("Y: %.1f", minY), MARGIN - 40, WINDOW_HEIGHT - MARGIN);
        g2d.drawString(String.format("Y: %.1f", maxY), MARGIN - 40, MARGIN + 10);
    }

    /**
     * 绘制实体形状边框
     * 使用Area实体的顶点列表绘制其实际几何形状
     */
    protected void drawEntityShape(Graphics2D g2d, Area area, Color color) {
        try {
            // 获取实体的顶点列表（int[]数组）
            int[] apexArray = area.getApexList();
            if (apexArray == null || apexArray.length < 6) { // 至少需要3个点（每个点2个坐标）
                return;
            }

            // 创建多边形对象
            Polygon polygon = new Polygon();

            // 将int[]顶点数据转换为屏幕坐标并添加到多边形
            // 顶点数据格式：[x1, y1, x2, y2, x3, y3, ...]
            for (int i = 0; i < apexArray.length; i += 2) {
                if (i + 1 < apexArray.length) {
                    double worldX = apexArray[i];
                    double worldY = apexArray[i + 1];
                    Point screenPoint = worldToScreen(worldX, worldY);
                    polygon.addPoint(screenPoint.x, screenPoint.y);
                }
            }

            // 设置填充颜色
            Color fillColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), 255);
            g2d.setColor(fillColor);
            g2d.fillPolygon(polygon);

            // 设置边框颜色
            g2d.setColor(color);
            g2d.drawPolygon(polygon);

        } catch (Exception e) {
            // 如果获取顶点失败，回退到绘制中心点
            System.err.println("绘制实体形状时出错: " + e.getMessage());
            Point screenPos = worldToScreen(area.getX(), area.getY());
            g2d.fillOval(screenPos.x - POINT_SIZE / 2, screenPos.y - POINT_SIZE / 2, POINT_SIZE, POINT_SIZE);
        }
    }

    /**
     * 绘制所有实体
     */
    protected void drawEntities(Graphics2D g2d) {
        for (StandardEntity entity : allEntities) {
            if (entity instanceof Area) {
                Area area = (Area) entity;

                // 根据实体类型设置颜色
                StandardEntityURN urn = entity.getStandardURN();
                Color color = entityColors.getOrDefault(urn, Color.GRAY);

                // 绘制实体形状（新增）
                drawEntityShape(g2d, area, color);

                // 绘制实体中心点（用于交互）
                Point screenPos = worldToScreen(area.getX(), area.getY());
                g2d.setColor(color);
                g2d.fillOval(screenPos.x - POINT_SIZE / 2, screenPos.y - POINT_SIZE / 2, POINT_SIZE, POINT_SIZE);
                g2d.setColor(Color.WHITE);
                g2d.drawOval(screenPos.x - POINT_SIZE / 2, screenPos.y - POINT_SIZE / 2, POINT_SIZE, POINT_SIZE);
            }
        }
    }

    /**
     * 绘制聚类结果
     */
    protected void drawClusters(Graphics2D g2d) {
        int clusterCount = clustering.getClusterNumber();

        // 绘制每个聚类
        for (int i = 0; i < clusterCount; i++) {
            Color clusterColor = clusterColors[i];

            // 计算聚类中心（通过成员位置的平均值）
            double centerX = 0, centerY = 0;
            int memberCount = 0;
            Collection<EntityID> members = clustering.getClusterEntityIDs(i);

            if (members != null) {
                // 计算聚类中心
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

                    // 绘制聚类中心
                    Point centerScreen = worldToScreen(centerX, centerY);
                    g2d.setColor(clusterColor);
                    g2d.fillRect(centerScreen.x - CENTER_SIZE / 2, centerScreen.y - CENTER_SIZE / 2, CENTER_SIZE, CENTER_SIZE);
                    g2d.setColor(Color.WHITE);
                    g2d.drawRect(centerScreen.x - CENTER_SIZE / 2, centerScreen.y - CENTER_SIZE / 2, CENTER_SIZE, CENTER_SIZE);

                    // 绘制聚类成员和连线
                    for (EntityID memberId : members) {
                        StandardEntity member = findEntityById(memberId);
                        if (member instanceof Area) {
                            Area area = (Area) member;
                            Point memberScreen = worldToScreen(area.getX(), area.getY());

                            // 绘制从成员到中心的连线
                            g2d.setColor(clusterColor);
                            g2d.drawLine(centerScreen.x, centerScreen.y, memberScreen.x, memberScreen.y);

                            // 绘制成员点（用聚类颜色，与文档2一致）
                            g2d.fillOval(memberScreen.x - POINT_SIZE / 2, memberScreen.y - POINT_SIZE / 2, 
                                       POINT_SIZE, POINT_SIZE);
                        }
                    }
                }
            }

            // 绘制聚类边界
            drawClusterBoundary(g2d, i, clusterColor);
        }
    }

    /**
     * 通过实体ID查找实体
     */
    protected StandardEntity findEntityById(EntityID id) {
        for (StandardEntity entity : allEntities) {
            if (entity.getID().equals(id)) {
                return entity;
            }
        }
        return null;
    }

    /**
     * 绘制聚类边界（简单实现，使用成员点的边界框）
     */
    protected void drawClusterBoundary(Graphics2D g2d, int clusterIndex, Color color) {
        Collection<EntityID> members = clustering.getClusterEntityIDs(clusterIndex);
        if (members == null || members.isEmpty()) {
            return;
        }

        List<Point> memberPoints = new ArrayList<>();
        for (EntityID memberId : members) {
            StandardEntity member = findEntityById(memberId);
            if (member instanceof Area) {
                Area area = (Area) member;
                memberPoints.add(worldToScreen(area.getX(), area.getY()));
            }
        }

        if (memberPoints.size() < 3) {
            return;
        }

        // 计算边界框
        int minScreenX = Integer.MAX_VALUE, maxScreenX = Integer.MIN_VALUE;
        int minScreenY = Integer.MAX_VALUE, maxScreenY = Integer.MIN_VALUE;

        for (Point p : memberPoints) {
            minScreenX = Math.min(minScreenX, p.x);
            maxScreenX = Math.max(maxScreenX, p.x);
            minScreenY = Math.min(minScreenY, p.y);
            maxScreenY = Math.max(maxScreenY, p.y);
        }

        // 绘制边界框
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 50));
        g2d.fillRect(minScreenX - 10, minScreenY - 10,
                maxScreenX - minScreenX + 20, maxScreenY - minScreenY + 20);
        g2d.setColor(color);
        g2d.drawRect(minScreenX - 10, minScreenY - 10,
                maxScreenX - minScreenX + 20, maxScreenY - minScreenY + 20);

        // 绘制聚类标签
        g2d.drawString("Cluster " + clusterIndex, minScreenX - 10, minScreenY - 15);
    }

    /**
     * 绘制图例
     */
    protected void drawLegend(Graphics2D g2d) {
        int legendX = WINDOW_WIDTH - 200;
        int legendY = 50;
        int lineHeight = 20;

        g2d.setColor(Color.WHITE);
        g2d.drawString("Legend:", legendX, legendY);

        int index = 1;
        for (Map.Entry<StandardEntityURN, Color> entry : entityColors.entrySet()) {
            g2d.setColor(entry.getValue());
            g2d.fillRect(legendX, legendY + index * lineHeight, 15, 10);
            g2d.setColor(Color.WHITE);
            g2d.drawString(entry.getKey().toString(), legendX + 20, legendY + index * lineHeight + 9);
            index++;
        }

        // 添加聚类中心图例
        g2d.setColor(Color.YELLOW);
        g2d.fillRect(legendX, legendY + index * lineHeight, 15, 15);
        g2d.setColor(Color.WHITE);
        g2d.drawString("Cluster Center", legendX + 20, legendY + index * lineHeight + 12);
        
        index++;
        
        // 添加聚类成员图例
        g2d.setColor(Color.RED);
        g2d.fillOval(legendX, legendY + index * lineHeight, 15, 15);
        g2d.setColor(Color.WHITE);
        g2d.drawString("Cluster Member", legendX + 20, legendY + index * lineHeight + 12);
    }

    /**
     * 显示实体信息
     */
    protected void showEntityInfo(int screenX, int screenY) {
        // 查找点击的实体
        for (StandardEntity entity : allEntities) {
            if (entity instanceof Area) {
                Area area = (Area) entity;
                Point entityScreen = worldToScreen(area.getX(), area.getY());
                double distance = Math.hypot(entityScreen.x - screenX, entityScreen.y - screenY);

                if (distance <= POINT_SIZE) {
                    int clusterIndex = clustering.getClusterIndex(entity);
                    String clusterInfo = (clusterIndex >= 0)
                            ? "Cluster: " + clusterIndex : "Not assigned";

                    // 在控制台输出信息
                    System.out.println("Entity: " + entity.getID()
                            + ", Type: " + entity.getStandardURN()
                            + ", Position: (" + area.getX() + ", " + area.getY() + ")"
                            + ", " + clusterInfo);
                    break;
                }
            }
        }
    }

    /**
     * 保存当前画面为PNG文件
     */
    public void saveScreenshot() {
        try {
            // 创建BufferedImage来捕获屏幕内容
            BufferedImage image = new BufferedImage(WINDOW_WIDTH, WINDOW_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();

            // 设置渲染质量
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 绘制背景
            g2d.setColor(Color.BLACK);
            g2d.fillRect(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);

            // 调用paint方法绘制所有内容
            paint(g2d);

            // 生成文件名（包含时间戳和算法类名）
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String timestamp = dateFormat.format(new Date());
            String className = clustering.getClass().getSimpleName();
            String fileName = "clustering_" + className + "_" + timestamp + ".png";

            // 创建输出目录（如果不存在）
            File outputDir = new File("visualization_output");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            // 保存图像
            File outputFile = new File(outputDir, fileName);
            ImageIO.write(image, "png", outputFile);

            System.out.println("画面已保存至: " + outputFile.getAbsolutePath());

            g2d.dispose();
        } catch (Exception e) {
            System.err.println("保存画面时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 启动可视化并自动保存画面
     */
    public void visualize() {
        setVisible(true);

        // 延迟一段时间后自动保存画面
        try {
            Thread.sleep(2000); // 等待2秒确保画面完全渲染
            saveScreenshot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 手动保存画面（可用于按钮触发等）
     */
    public void saveVisualization() {
        saveScreenshot();
    }

    /**
     * 主方法用于测试
     */
    public static void main(String[] args) {
        System.out.println("Static Clustering Visualizer - 请在救援模拟环境中使用");
    }
}