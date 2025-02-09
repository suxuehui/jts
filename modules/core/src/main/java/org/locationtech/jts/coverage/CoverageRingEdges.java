/*
 * Copyright (c) 2022 Martin Davis.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * and Eclipse Distribution License v. 1.0 which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */
package org.locationtech.jts.coverage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateList;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

/**
 * Models a polygonal coverage as a set of unique {@link CoverageEdge}s,
 * linked to the parent rings in the coverage polygons.
 * Each edge has either one or two parent rings, depending on whether 
 * it is an inner or outer edge of the coverage.
 * The source coverage is represented as a array of polygonal geometries 
 * (either {@link Polygon}s or {@link MultiPolygon}s).
 * 
 * @author Martin Davis
 *
 */
class CoverageRingEdges {
  
  /**
   * Create a new instance for a given coverage.
   * 
   * @param coverage the set of polygonal geometries in the coverage
   * @return the edges of the coverage
   */
  public static CoverageRingEdges create(Geometry[] coverage) {
    CoverageRingEdges edges = new CoverageRingEdges(coverage);
    return edges;
  }
  
  private Geometry[] coverage;
  private Map<LinearRing, List<CoverageEdge>> ringEdgesMap;
  private List<CoverageEdge> edges;
  
  public CoverageRingEdges(Geometry[] coverage) {
    this.coverage = coverage;
    ringEdgesMap = new HashMap<LinearRing, List<CoverageEdge>>();
    edges = new ArrayList<CoverageEdge>();
    build();
  }

  public List<CoverageEdge> getEdges() {
    return edges;
  }
  
  /**
   * Selects the edges with a given ring count (which can be 1 or 2).
   * 
   * @param ringCount the edge ring count to select (1 or 2)
   * @return the selected edges
   */
  public List<CoverageEdge> selectEdges(int ringCount) {
    List<CoverageEdge> result = new ArrayList<CoverageEdge>();
    for (CoverageEdge edge : edges) {
      if (edge.getRingCount() == ringCount) {
        result.add(edge);
      }
    }
    return result;
  }
  
  private void build() {
    Set<Coordinate> nodes = findNodes(coverage);
    Set<LineSegment> boundarySegs = CoverageBoundarySegmentFinder.findBoundarySegments(coverage);
    nodes.addAll(findBoundaryNodes(boundarySegs));
    HashMap<LineSegment, CoverageEdge> uniqueEdgeMap = new HashMap<LineSegment, CoverageEdge>();
    for (Geometry geom : coverage) {
      for (int ipoly = 0; ipoly < geom.getNumGeometries(); ipoly++) {
        Polygon poly = (Polygon) geom.getGeometryN(ipoly);
        //-- extract shell
        LinearRing shell = poly.getExteriorRing();
        addRingEdges(shell, nodes, boundarySegs, uniqueEdgeMap);
        //-- extract holes
        for (int ihole = 0; ihole < poly.getNumInteriorRing(); ihole++) {
          LinearRing hole = poly.getInteriorRingN(ihole);
          addRingEdges(hole, nodes, boundarySegs, uniqueEdgeMap);         
        }
      }
    }
  }

  private void addRingEdges(LinearRing ring, Set<Coordinate> nodes, Set<LineSegment> boundarySegs,
      HashMap<LineSegment, CoverageEdge> uniqueEdgeMap) {
    addBoundaryNodes(ring, boundarySegs, nodes);
    List<CoverageEdge> ringEdges = extractRingEdges(ring, uniqueEdgeMap, nodes);
    ringEdgesMap.put(ring, ringEdges);
  }

  private void addBoundaryNodes(LinearRing ring, Set<LineSegment> boundarySegs, Set<Coordinate> nodes) {
    CoordinateSequence seq = ring.getCoordinateSequence();
    boolean isBdyLast = isBoundarySegment(seq, seq.size() - 2, boundarySegs);
    boolean isBdyPrev = isBdyLast;
    for (int i = 0; i < seq.size() - 1; i++) {
      boolean isBdy = isBoundarySegment(seq, i, boundarySegs);
      if (isBdy != isBdyPrev) {
        Coordinate nodePt = seq.getCoordinate(i);
        nodes.add(nodePt);
      }
      isBdyPrev = isBdy;
    }
  }

  private boolean isBoundarySegment(CoordinateSequence seq, int i, Set<LineSegment> boundarySegs) {
    LineSegment seg = CoverageBoundarySegmentFinder.createSegment(seq, i);
    return boundarySegs.contains(seg);
  }

  private List<CoverageEdge> extractRingEdges(LinearRing ring, 
      HashMap<LineSegment, CoverageEdge> uniqueEdgeMap, 
      Set<Coordinate> nodes) {
    List<CoverageEdge> ringEdges = new ArrayList<CoverageEdge>();
    int first = findNextNodeIndex(ring, -1, nodes);
    if (first < 0) {
      //-- ring does not contain a node, so edge is entire ring
      CoverageEdge edge = createEdge(ring, uniqueEdgeMap);
      ringEdges.add(edge);
    }
    else {
      int start = first;
      int end = start;
      do {
        end = findNextNodeIndex(ring, start, nodes);
        CoverageEdge edge = createEdge(ring, start, end, uniqueEdgeMap);
        ringEdges.add(edge);
        start = end;
      } while (end != first);
    }
    return ringEdges;
  }

  private CoverageEdge createEdge(LinearRing ring, HashMap<LineSegment, CoverageEdge> uniqueEdgeMap) {
    CoverageEdge edge;
    LineSegment edgeKey = CoverageEdge.key(ring);
    if (uniqueEdgeMap.containsKey(edgeKey)) {
      edge = uniqueEdgeMap.get(edgeKey);
    }
    else {
      edge = CoverageEdge.createEdge(ring);
      uniqueEdgeMap.put(edgeKey, edge);
      edges.add(edge);
    }
    edge.incRingCount();
    return edge;
  }
  
  private CoverageEdge createEdge(LinearRing ring, int start, int end, HashMap<LineSegment, CoverageEdge> uniqueEdgeMap) {
    CoverageEdge edge;
    LineSegment edgeKey = (end == start) ? CoverageEdge.key(ring) : CoverageEdge.key(ring, start, end);
    if (uniqueEdgeMap.containsKey(edgeKey)) {
      edge = uniqueEdgeMap.get(edgeKey);
    }
    else {
      edge = CoverageEdge.createEdge(ring, start, end);
      uniqueEdgeMap.put(edgeKey, edge);
      edges.add(edge);
    }
    edge.incRingCount();
    return edge;
  }

  private int findNextNodeIndex(LinearRing ring, int start, Set<Coordinate> nodes) {
    int index = start;
    boolean isScanned0 = false;
    do {
      index = next(index, ring);
      if (index == 0) {
        if (start < 0 && isScanned0) 
          return -1;
        isScanned0 = true;
      }
      Coordinate pt = ring.getCoordinateN(index);
      if (nodes.contains(pt)) {
        return index;
      }
    } while (index != start);
    return -1;
  }

  private static int next(int index, LinearRing ring) {
    index = index + 1;
    if (index >= ring.getNumPoints() - 1)
      index = 0;
    return index;
  }

  private Set<Coordinate> findNodes(Geometry[] coverage) {
    Map<Coordinate, Integer> vertexCount = VertexCounter.count(coverage);
    Set<Coordinate> nodes = new HashSet<Coordinate>();
    for (Coordinate v : vertexCount.keySet()) {
      if (vertexCount.get(v) > 2) {
        nodes.add(v);
      }
    }
    return nodes;
  }


  private Set<Coordinate> findBoundaryNodes(Set<LineSegment> lineSegments) {
    Map<Coordinate, Integer> counter = new HashMap<>();
    for (LineSegment line : lineSegments) {
      counter.put(line.p0, counter.getOrDefault(line.p0, 0) + 1);
      counter.put(line.p1, counter.getOrDefault(line.p1, 0) + 1);
    }
    return counter.entrySet().stream()
        .filter(e->e.getValue()>2)
        .map(Map.Entry::getKey).collect(Collectors.toSet());
  }

  /**
   * Recreates the polygon coverage from the current edge values.
   * 
   * @return an array of polygonal geometries representing the coverage
   */
  public Geometry[] buildCoverage() {
    Geometry[] result = new Geometry[coverage.length];
    for (int i = 0; i < coverage.length; i++) {
      result[i] = buildPolygonal(coverage[i]);
    }
    return result;
  }

  private Geometry buildPolygonal(Geometry geom) {
    if (geom instanceof MultiPolygon) {
      return buildMultiPolygon((MultiPolygon) geom);
    }
    else {
      return buildPolygon((Polygon) geom);
    }
  }

  private Geometry buildMultiPolygon(MultiPolygon geom) {
    Polygon[] polys = new Polygon[geom.getNumGeometries()];
    for (int i = 0; i < polys.length; i++) {
      polys[i] = buildPolygon((Polygon) geom.getGeometryN(i));
    }
    return geom.getFactory().createMultiPolygon(polys);
  }

  private Polygon buildPolygon(Polygon polygon) {
    LinearRing shell = buildRing(polygon.getExteriorRing());
    if (polygon.getNumInteriorRing() == 0) {
      return polygon.getFactory().createPolygon(shell);
    }
    LinearRing holes[] = new LinearRing[polygon.getNumInteriorRing()];
    for (int i = 0; i < holes.length; i++) {
      LinearRing hole = polygon.getInteriorRingN(i);
      holes[i] = buildRing(hole);
    }
    return polygon.getFactory().createPolygon(shell, holes);
  }

  private LinearRing buildRing(LinearRing ring) {
    List<CoverageEdge> ringEdges = ringEdgesMap.get(ring);
    CoordinateList ptsList = new CoordinateList();
    for (int i = 0; i < ringEdges.size(); i++) {
      Coordinate lastPt = ptsList.size() > 0 
                            ? ptsList.getCoordinate(ptsList.size() - 1)
                            : null;
      boolean dir = isEdgeDirForward(ringEdges, i, lastPt);
      ptsList.add(ringEdges.get(i).getCoordinates(), false, dir);
    }
    Coordinate[] pts = ptsList.toCoordinateArray();
    return ring.getFactory().createLinearRing(pts);
  }

  private boolean isEdgeDirForward(List<CoverageEdge> ringEdges, int index, Coordinate prevPt) {
    int size = ringEdges.size();
    if (size <= 1) return true;
    if (index == 0) {
      //-- if only 2 edges, first one can keep orientation
      if (size == 2)
        return true;
      Coordinate endPt0 = ringEdges.get(0).getEndCoordinate();
      return endPt0.equals2D(ringEdges.get(1).getStartCoordinate())
          || endPt0.equals2D(ringEdges.get(1).getEndCoordinate());
    }
    //-- previous point determines required orientation
    return prevPt.equals2D(ringEdges.get(index).getStartCoordinate());
  }

}
