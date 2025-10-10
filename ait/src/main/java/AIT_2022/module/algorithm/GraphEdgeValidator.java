package AIT_2022.module.algorithm;

import rescuecore2.standard.entities.*;
import rescuecore2.misc.geometry.*;
import org.locationtech.jts.geom.*;
import java.util.*;
import java.util.stream.*;

import static java.util.stream.Collectors.*;

public class GraphEdgeValidator {

  private static final double AGENT_RADIUS = 500.0;

  public static boolean validate(Geometry passable, Line2D from, Line2D next) {
    final Geometry line1 = convert(from);
    final Geometry line2 = convert(next);

    boolean ret = false;
    for (Geometry geom : decompose(passable)) {
      ret |= geom.intersects(line1) && geom.intersects(line2);
    }

    return ret;
  }

  public static Geometry computePassable(
      Area area, Collection<Blockade> blockades) {
    final GeometryFactory fact = new GeometryFactory();
    final Geometry obstacle = blockades
        .stream()
        .map(GraphEdgeValidator::convert)
        .map(GraphEdgeValidator::buffer)
        .reduce(Geometry::union)
        .orElse(fact.createPolygon());

    final Geometry geom = buffer(convert(area), gatherImpassables(area));
    return geom.difference(obstacle).norm().buffer(1.0);
  }

  private static Collection<Line2D> gatherImpassables(Area area) {
    final Stream<Line2D> ret = area.getEdges()
        .stream().filter(e -> !e.isPassable()).map(Edge::getLine);
    return ret.collect(toList());
  }

  private static Geometry convert(Area area) {
    final int[] apexes = area.getApexList();
    return convert(apexes);
  }

  private static Geometry convert(Blockade blockade) {
    final int[] apexes = blockade.getApexes();
    return convert(apexes);
  }

  private static Geometry convert(int[] apexes) {
    final int n = apexes.length / 2;
    Coordinate[] cs = new Coordinate[n + 1];
    for (int i = 0; i < n; ++i) {
      cs[i] = new Coordinate(apexes[2 * i], apexes[2 * i + 1]);
    }
    cs[n] = cs[0];

    final GeometryFactory fact = new GeometryFactory();
    return fact.createPolygon(cs);
  }

  private static Geometry convert(Line2D line) {
    final int n = 2;
    Coordinate[] cs = new Coordinate[n];
    for (int i = 0; i < n; ++i) {
      final Point2D p = line.getPoint(1.0 * i);
      cs[i] = new Coordinate(p.getX(), p.getY());
    }

    final GeometryFactory fact = new GeometryFactory();
    final LineString ret = fact.createLineString(cs);
    return ret.getLength() > 0.0 ? ret : ret.getPointN(0);
  }

  private static Geometry buffer(Geometry geom, Collection<Line2D> lines) {
    final GeometryFactory fact = new GeometryFactory();
    final Geometry bufferer = lines
        .stream()
        .map(GraphEdgeValidator::convert)
        .map(GraphEdgeValidator::buffer)
        .reduce(Geometry::union)
        .orElse(fact.createPolygon());

    return geom.difference(bufferer);
  }

  private static Geometry buffer(Geometry geom) {
    return geom.buffer(AGENT_RADIUS);
  }

  private static Collection<Geometry> decompose(Geometry geom) {
    if (!GeometryCollection.class.isInstance(geom)) {
      return Collections.singleton(geom);
    }

    final GeometryCollection collection = (GeometryCollection) geom;
    final int n = collection.getNumGeometries();

    List<Geometry> ret = new ArrayList<>(n);
    for (int i = 0; i < n; ++i) {
      ret.add(collection.getGeometryN(i));
    }
    return ret;
  }
}
