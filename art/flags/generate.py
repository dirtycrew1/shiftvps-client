"""Convert pinned, flat flag-icons SVGs to shared native VectorDrawables.

Regeneration uses svglib/reportlab only; the application has no SVG dependency.
"""

import math
from pathlib import Path
from xml.etree import ElementTree as ET

from reportlab.graphics.shapes import Circle, Group, Path as ShapePath, Rect
from reportlab.graphics.transform import mmult, transformPoint
from svglib.svglib import svg2rlg


ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)
SOURCE = Path(__file__).resolve().parent
TARGET = SOURCE.parents[1] / "composeApp/src/commonMain/composeResources/drawable"


def attribute(name):
    return f"{{{ANDROID}}}{name}"


def number(value):
    return f"{value:.5f}".rstrip("0").rstrip(".") or "0"


def color(value):
    return "#" + "".join(f"{round(component * 255):02X}" for component in (value.red, value.green, value.blue))


def as_path(shape):
    if isinstance(shape, ShapePath):
        return shape
    path = ShapePath()
    if isinstance(shape, Rect):
        assert not shape.rx and not shape.ry
        path.moveTo(shape.x, shape.y)
        path.lineTo(shape.x + shape.width, shape.y)
        path.lineTo(shape.x + shape.width, shape.y + shape.height)
        path.lineTo(shape.x, shape.y + shape.height)
        path.closePath()
    elif isinstance(shape, Circle):
        x, y, radius = shape.cx, shape.cy, shape.r
        control = radius * 0.552284749831
        path.moveTo(x + radius, y)
        path.curveTo(x + radius, y + control, x + control, y + radius, x, y + radius)
        path.curveTo(x - control, y + radius, x - radius, y + control, x - radius, y)
        path.curveTo(x - radius, y - control, x - control, y - radius, x, y - radius)
        path.curveTo(x + control, y - radius, x + radius, y - control, x + radius, y)
        path.closePath()
    else:
        raise ValueError(f"Unsupported pinned SVG primitive: {type(shape).__name__}")
    return path


def path_data(path, matrix):
    output = []
    offset = 0
    for operation in path.operators:
        count = {0: 2, 1: 2, 2: 6, 3: 0}[operation]
        points = path.points[offset:offset + count]
        output.append({0: "M", 1: "L", 2: "C", 3: "Z"}[operation])
        for index in range(0, count, 2):
            output.append(" ".join(number(value) for value in transformPoint(matrix, points[index:index + 2])))
        offset += count
    assert offset == len(path.points)
    return " ".join(output)


def append_node(shape, parent, matrix):
    if isinstance(shape, Group):
        group = ET.SubElement(parent, "group")
        combined = mmult(matrix, shape.transform)
        for child in shape.contents:
            append_node(child, group, combined)
        return
    path = as_path(shape)
    data = path_data(path, matrix)
    if getattr(shape, "isClipPath", False):
        ET.SubElement(parent, "clip-path", {attribute("pathData"): data})
        return
    fill = shape.fillColor
    stroke = shape.strokeColor
    if fill is None and stroke is None:
        return
    attributes = {attribute("pathData"): data, attribute("fillType"): "evenOdd" if shape.fillMode == 0 else "nonZero"}
    if fill is not None:
        attributes[attribute("fillColor")] = color(fill)
        attributes[attribute("fillAlpha")] = number((shape.fillOpacity or 1) * fill.alpha)
    if stroke is not None:
        attributes[attribute("strokeColor")] = color(stroke)
        attributes[attribute("strokeAlpha")] = number((shape.strokeOpacity or 1) * stroke.alpha)
        attributes[attribute("strokeWidth")] = number(shape.strokeWidth * math.sqrt(abs(matrix[0] * matrix[3] - matrix[1] * matrix[2])))
        attributes[attribute("strokeLineCap")] = {0: "butt", 1: "round", 2: "square"}[shape.strokeLineCap]
        attributes[attribute("strokeLineJoin")] = {0: "miter", 1: "round", 2: "bevel"}[shape.strokeLineJoin]
    ET.SubElement(parent, "path", attributes)


def main():
    for source in sorted(SOURCE.glob("*.svg")):
        drawing = svg2rlg(str(source))
        vector = ET.Element("vector", {
            attribute("width"): "28dp", attribute("height"): "21dp",
            attribute("viewportWidth"): number(drawing.width), attribute("viewportHeight"): number(drawing.height),
        })
        append_node(drawing, vector, (1, 0, 0, -1, 0, drawing.height))
        ET.indent(vector, space="  ")
        output = TARGET / f"flag_{source.stem}.xml"
        output.write_text(ET.tostring(vector, encoding="unicode") + "\n", encoding="utf-8")
        print(output.name)


if __name__ == "__main__":
    main()
