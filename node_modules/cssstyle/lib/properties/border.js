"use strict";

const parsers = require("../parsers");
const borderWidth = require("./borderWidth");
const borderStyle = require("./borderStyle");
const borderColor = require("./borderColor");
const borderTop = require("./borderTop");
const borderRight = require("./borderRight");
const borderBottom = require("./borderBottom");
const borderLeft = require("./borderLeft");

const property = "border";

const subProps = {
  width: borderWidth.property,
  style: borderStyle.property,
  color: borderColor.property
};

module.exports.initialValues = new Map([
  [borderWidth.property, "medium"],
  [borderStyle.property, "none"],
  [borderColor.property, "currentcolor"]
]);

module.exports.shorthandFor = new Map([
  [borderWidth.property, borderWidth],
  [borderStyle.property, borderStyle],
  [borderColor.property, borderColor]
]);

module.exports.positionShorthandFor = new Map([
  [borderTop.property, borderTop],
  [borderRight.property, borderRight],
  [borderBottom.property, borderBottom],
  [borderLeft.property, borderLeft]
]);

module.exports.parse = (v, opt = {}) => {
  const { globalObject } = opt;
  if (v === "" || parsers.hasVarFunc(v)) {
    return v;
  }
  const values = parsers.splitValue(v);
  const parsedValues = new Map();
  for (const val of values) {
    const value = parsers.parsePropertyValue(property, val, {
      globalObject,
      inArray: true
    });
    if (Array.isArray(value) && value.length === 1) {
      const parsedValue = parsers.resolveBorderShorthandValue(value, subProps, parsedValues);
      if (typeof parsedValue === "string") {
        return parsedValue;
      } else if (Array.isArray(parsedValue)) {
        const [key, resolvedVal] = parsedValue;
        parsedValues.set(key, resolvedVal);
      } else {
        return;
      }
    } else {
      return;
    }
  }
  if (parsedValues.size) {
    const keys = module.exports.shorthandFor.keys();
    const obj = {
      [borderWidth.property]: "medium"
    };
    for (const key of keys) {
      if (parsedValues.has(key)) {
        const parsedValue = parsedValues.get(key);
        if (parsedValue !== module.exports.initialValues.get(key)) {
          obj[key] = parsedValues.get(key);
          if (obj[borderWidth.property] && obj[borderWidth.property] === "medium") {
            delete obj[borderWidth.property];
          }
        }
      }
    }
    return obj;
  }
};

module.exports.definition = {
  set(v) {
    v = parsers.prepareValue(v);
    if (parsers.hasVarFunc(v)) {
      this._borderSetter(property, v, "");
    } else {
      const val = module.exports.parse(v, {
        globalObject: this._global
      });
      if (val || typeof val === "string") {
        const priority = this._priorities.get(property) ?? "";
        this._borderSetter(property, val, priority);
      }
    }
  },
  get() {
    return this.getPropertyValue(property);
  },
  enumerable: true,
  configurable: true
};

module.exports.property = property;
