"use strict";

const {
  resolve: resolveColor,
  utils: { cssCalc, resolveGradient, splitValue }
} = require("@asamuzakjp/css-color");
const { next: syntaxes } = require("@csstools/css-syntax-patches-for-csstree");
const csstree = require("css-tree");
const { LRUCache } = require("lru-cache");
const { asciiLowercase } = require("./utils/strings");

// CSS global keywords
// @see https://drafts.csswg.org/css-cascade-5/#defaulting-keywords
const GLOBAL_KEYS = new Set(["initial", "inherit", "unset", "revert", "revert-layer"]);

// System colors
// @see https://drafts.csswg.org/css-color/#css-system-colors
// @see https://drafts.csswg.org/css-color/#deprecated-system-colors
const SYS_COLORS = new Set([
  "accentcolor",
  "accentcolortext",
  "activeborder",
  "activecaption",
  "activetext",
  "appworkspace",
  "background",
  "buttonborder",
  "buttonface",
  "buttonhighlight",
  "buttonshadow",
  "buttontext",
  "canvas",
  "canvastext",
  "captiontext",
  "field",
  "fieldtext",
  "graytext",
  "highlight",
  "highlighttext",
  "inactiveborder",
  "inactivecaption",
  "inactivecaptiontext",
  "infobackground",
  "infotext",
  "linktext",
  "mark",
  "marktext",
  "menu",
  "menutext",
  "scrollbar",
  "selecteditem",
  "selecteditemtext",
  "threeddarkshadow",
  "threedface",
  "threedhighlight",
  "threedlightshadow",
  "threedshadow",
  "visitedtext",
  "window",
  "windowframe",
  "windowtext"
]);

// AST node types
const AST_TYPES = Object.freeze({
  CALC: "Calc",
  DIMENSION: "Dimension",
  FUNCTION: "Function",
  GLOBAL_KEYWORD: "GlobalKeyword",
  HASH: "Hash",
  IDENTIFIER: "Identifier",
  NUMBER: "Number",
  PERCENTAGE: "Percentage",
  STRING: "String",
  URL: "Url"
});

// Regular expressions
const CALC_FUNC_NAMES =
  "(?:a?(?:cos|sin|tan)|abs|atan2|calc|clamp|exp|hypot|log|max|min|mod|pow|rem|round|sign|sqrt)";
const calcRegEx = new RegExp(`^${CALC_FUNC_NAMES}\\(`);
const calcContainedRegEx = new RegExp(`(?<=[*/\\s(])${CALC_FUNC_NAMES}\\(`);
const calcNameRegEx = new RegExp(`^${CALC_FUNC_NAMES}$`);
const varRegEx = /^var\(/;
const varContainedRegEx = /(?<=[*/\s(])var\(/;

// Patched css-tree
const cssTree = csstree.fork(syntaxes);

// Instance of the LRU Cache. Stores up to 4096 items.
const lruCache = new LRUCache({
  max: 4096
});

/**
 * Prepares a stringified value.
 *
 * @param {string|number|null|undefined} value - The value to prepare.
 * @returns {string} The prepared value.
 */
const prepareValue = (value) => {
  // `null` is converted to an empty string.
  // @see https://webidl.spec.whatwg.org/#LegacyNullToEmptyString
  if (value === null) {
    return "";
  }
  return `${value}`.trim();
};

/**
 * Checks if the value is a global keyword.
 *
 * @param {string} val - The value to check.
 * @returns {boolean} True if the value is a global keyword, false otherwise.
 */
const isGlobalKeyword = (val) => {
  return GLOBAL_KEYS.has(asciiLowercase(val));
};

/**
 * Checks if the value starts with or contains a CSS var() function.
 *
 * @param {string} val - The value to check.
 * @returns {boolean} True if the value contains a var() function, false otherwise.
 */
const hasVarFunc = (val) => {
  return varRegEx.test(val) || varContainedRegEx.test(val);
};

/**
 * Checks if the value starts with or contains CSS calc() or math functions.
 *
 * @param {string} val - The value to check.
 * @returns {boolean} True if the value contains calc() or math functions, false otherwise.
 */
const hasCalcFunc = (val) => {
  return calcRegEx.test(val) || calcContainedRegEx.test(val);
};

/**
 * Parses a CSS string into an AST.
 *
 * @param {string} val - The CSS string to parse.
 * @param {object} opt - The options for parsing.
 * @param {boolean} [toObject=false] - Whether to return a plain object.
 * @returns {object} The AST or a plain object.
 */
const parseCSS = (val, opt, toObject = false) => {
  val = prepareValue(val);
  const ast = cssTree.parse(val, opt);
  if (toObject) {
    return cssTree.toPlainObject(ast);
  }
  return ast;
};

/**
 * Checks if the value is a valid property value.
 * Returns false for custom properties or values containing var().
 *
 * @param {string} prop - The property name.
 * @param {string} val - The property value.
 * @returns {boolean} True if the value is valid, false otherwise.
 */
const isValidPropertyValue = (prop, val) => {
  val = prepareValue(val);
  if (val === "") {
    return true;
  }
  // cssTree.lexer does not support deprecated system colors
  // @see https://github.com/w3c/webref/issues/1519#issuecomment-3120290261
  // @see https://github.com/w3c/webref/issues/1647
  if (SYS_COLORS.has(asciiLowercase(val))) {
    if (/^(?:-webkit-)?(?:[a-z][a-z\d]*-)*color$/i.test(prop)) {
      return true;
    }
    return false;
  }
  const cacheKey = `isValidPropertyValue_${prop}_${val}`;
  const cachedValue = lruCache.get(cacheKey);
  if (typeof cachedValue === "boolean") {
    return cachedValue;
  }
  let result;
  try {
    const ast = parseCSS(val, {
      context: "value"
    });
    const { error, matched } = cssTree.lexer.matchProperty(prop, ast);
    result = error === null && matched !== null;
  } catch {
    result = false;
  }
  lruCache.set(cacheKey, result);
  return result;
};

/**
 * Resolves CSS math functions.
 *
 * @param {string} val - The value to resolve.
 * @param {object} [opt={ format: "specifiedValue" }] - The options for resolving.
 * @returns {string|undefined} The resolved value.
 */
const resolveCalc = (val, opt = { format: "specifiedValue" }) => {
  val = prepareValue(val);
  if (val === "" || hasVarFunc(val) || !hasCalcFunc(val)) {
    return val;
  }
  const cacheKey = `resolveCalc_${val}`;
  const cachedValue = lruCache.get(cacheKey);
  if (typeof cachedValue === "string") {
    return cachedValue;
  }
  const obj = parseCSS(val, { context: "value" }, true);
  if (!obj?.children) {
    return;
  }
  const { children: items } = obj;
  const values = [];
  for (const item of items) {
    const { type: itemType, name: itemName, value: itemValue } = item;
    if (itemType === AST_TYPES.FUNCTION) {
      const value = cssTree
        .generate(item)
        .replace(/\)(?!\)|\s|,)/g, ") ")
        .trim();
      if (calcNameRegEx.test(itemName)) {
        const newValue = cssCalc(value, opt);
        values.push(newValue);
      } else {
        values.push(value);
      }
    } else if (itemType === AST_TYPES.STRING) {
      values.push(`"${itemValue}"`);
    } else {
      values.push(itemName ?? itemValue);
    }
  }
  const resolvedValue = values.join(" ");
  lruCache.set(cacheKey, resolvedValue);
  return resolvedValue;
};

/**
 * Parses a property value.
 * Returns a string or an array of parsed objects.
 *
 * @param {string} prop - The property name.
 * @param {string} val - The property value.
 * @param {object} [opt={}] - The options for parsing.
 * @returns {string|Array<object>|undefined} The parsed value.
 */
const parsePropertyValue = (prop, val, opt = {}) => {
  const { caseSensitive, inArray } = opt;
  val = prepareValue(val);
  if (val === "" || hasVarFunc(val)) {
    return val;
  } else if (hasCalcFunc(val)) {
    const calculatedValue = resolveCalc(val, {
      format: "specifiedValue"
    });
    if (typeof calculatedValue !== "string") {
      return;
    }
    val = calculatedValue;
  }
  const cacheKey = `parsePropertyValue_${prop}_${val}_${caseSensitive}`;
  const cachedValue = lruCache.get(cacheKey);
  if (cachedValue === false) {
    return;
  } else if (inArray) {
    if (Array.isArray(cachedValue)) {
      return cachedValue;
    }
  } else if (typeof cachedValue === "string") {
    return cachedValue;
  }
  let parsedValue;
  const lowerCasedValue = asciiLowercase(val);
  if (GLOBAL_KEYS.has(lowerCasedValue)) {
    if (inArray) {
      parsedValue = [
        {
          type: AST_TYPES.GLOBAL_KEYWORD,
          name: lowerCasedValue
        }
      ];
    } else {
      parsedValue = lowerCasedValue;
    }
  } else if (SYS_COLORS.has(lowerCasedValue)) {
    if (/^(?:(?:-webkit-)?(?:[a-z][a-z\d]*-)*color|border)$/i.test(prop)) {
      if (inArray) {
        parsedValue = [
          {
            type: AST_TYPES.IDENTIFIER,
            name: lowerCasedValue
          }
        ];
      } else {
        parsedValue = lowerCasedValue;
      }
    } else {
      parsedValue = false;
    }
  } else {
    try {
      const ast = parseCSS(val, {
        context: "value"
      });
      const { error, matched } = cssTree.lexer.matchProperty(prop, ast);
      if (error || !matched) {
        parsedValue = false;
      } else if (inArray) {
        const obj = cssTree.toPlainObject(ast);
        const items = obj.children;
        const values = [];
        for (const item of items) {
          const { children, name, type, value, unit } = item;
          switch (type) {
            case AST_TYPES.DIMENSION: {
              values.push({
                type,
                value,
                unit: asciiLowercase(unit)
              });
              break;
            }
            case AST_TYPES.FUNCTION: {
              const css = cssTree
                .generate(item)
                .replace(/\)(?!\)|\s|,)/g, ") ")
                .trim();
              const raw = items.length === 1 ? val : css;
              // Remove "${name}(" from the start and ")" from the end
              const itemValue = raw.slice(name.length + 1, -1).trim();
              if (name === "calc") {
                if (children.length === 1) {
                  const [child] = children;
                  if (child.type === AST_TYPES.NUMBER) {
                    values.push({
                      type: AST_TYPES.CALC,
                      isNumber: true,
                      value: `${parseFloat(child.value)}`,
                      name,
                      raw
                    });
                  } else {
                    values.push({
                      type: AST_TYPES.CALC,
                      isNumber: false,
                      value: `${asciiLowercase(itemValue)}`,
                      name,
                      raw
                    });
                  }
                } else {
                  values.push({
                    type: AST_TYPES.CALC,
                    isNumber: false,
                    value: asciiLowercase(itemValue),
                    name,
                    raw
                  });
                }
              } else {
                values.push({
                  type,
                  name,
                  value: asciiLowercase(itemValue),
                  raw
                });
              }
              break;
            }
            case AST_TYPES.IDENTIFIER: {
              if (caseSensitive) {
                values.push(item);
              } else {
                values.push({
                  type,
                  name: asciiLowercase(name)
                });
              }
              break;
            }
            default: {
              values.push(item);
            }
          }
        }
        parsedValue = values;
      } else {
        parsedValue = val;
      }
    } catch {
      parsedValue = false;
    }
  }
  lruCache.set(cacheKey, parsedValue);
  if (parsedValue === false) {
    return;
  }
  return parsedValue;
};

/**
 * Parses a numeric value (number, dimension, percentage).
 * Helper function for parseNumber, parseLength, etc.
 *
 * @param {Array<object>} val - The AST value.
 * @param {object} [opt={}] - The options for parsing.
 * @param {Function} validateType - Function to validate the node type.
 * @returns {object|undefined} The parsed result containing num and unit, or undefined.
 */
const parseNumericValue = (val, opt, validateType) => {
  const [item] = val;
  const { type, value, unit } = item ?? {};
  if (!validateType(type, value, unit)) {
    return;
  }
  const { clamp } = opt || {};
  const max = opt?.max ?? Number.INFINITY;
  const min = opt?.min ?? Number.NEGATIVE_INFINITY;
  let num = parseFloat(value);
  if (clamp) {
    if (num > max) {
      num = max;
    } else if (num < min) {
      num = min;
    }
  } else if (num > max || num < min) {
    return;
  }
  return {
    num,
    unit: unit ? asciiLowercase(unit) : null,
    type
  };
};

/**
 * Parses a <number> value.
 *
 * @param {Array<object>} val - The AST value.
 * @param {object} [opt={}] - The options for parsing.
 * @returns {string|undefined} The parsed number.
 */
const parseNumber = (val, opt = {}) => {
  const res = parseNumericValue(val, opt, (type) => type === AST_TYPES.NUMBER);
  if (!res) {
    return;
  }
  return `${res.num}`;
};

/**
 * Parses a <length> value.
 *
 * @param {Array<object>} val - The AST value.
 * @param {object} [opt={}] - The options for parsing.
 * @returns {string|undefined} The parsed length.
 */
const parseLength = (val, opt = {}) => {
  const res = parseNumericValue(
    val,
    opt,
    (type, value) => type === AST_TYPES.DIMENSION || (type === AST_TYPES.NUMBER && value === "0")
  );
  if (!res) {
    return;
  }
  const { num, unit } = res;
  if (num === 0 && !unit) {
    return `${num}px`;
  } else if (unit) {
    return `${num}${unit}`;
  }
};

/**
 * Parses a <percentage> value.
 *
 * @param {Array<object>} val - The AST value.
 * @param {object} [opt={}] - The options for parsing.
 * @returns {string|undefined} The parsed percentage.
 */
const parsePercentage = (val, opt = {}) => {
  const res = parseNumericValue(
    val,
    opt,
    (type, value) => type === AST_TYPES.PERCENTAGE || (type === AST_TYPES.NUMBER && value === "0")
  );
  if (!res) {
    return;
  }
  const { num } = res;
  return `${num}%`;
};

/**
 * Parses an <angle> value.
 *
 * @param {Array<object>} val - The AST value.
 * @param {object} [opt={}] - The options for parsing.
 * @returns {string|undefined} The parsed angle.
 */
const parseAngle = (val, opt = {}) => {
  const res = parseNumericValue(
    val,
    opt,
    (type, value) => type === AST_TYPES.DIMENSION || (type === AST_TYPES.NUMBER && value === "0")
  );
  if (!res) {
    return;
  }
  const { num, unit } = res;
  if (unit) {
    if (!/^(?:deg|g?rad|turn)$/i.test(unit)) {
      return;
    }
    return `${num}${unit}`;
  } else if (num === 0) {
    return `${num}deg`;
  }
};

/**
 * Parses a <url> value.
 *
 * @param {Array<object>} val - The AST value.
 * @returns {string|undefined} The parsed url.
 */
const parseUrl = (val) => {
  const [item] = val;
  const { type, value } = item ?? {};
  if (type !== AST_TYPES.URL) {
    return;
  }
  const str = value.replace(/\\\\/g, "\\").replaceAll('"', '\\"');
  return `url("${str}")`;
};

/**
 * Parses a <string> value.
 *
 * @param {Array<object>} val - The AST value.
 * @returns {string|undefined} The parsed string.
 */
const parseString = (val) => {
  const [item] = val;
  const { type, value } = item ?? {};
  if (type !== AST_TYPES.STRING) {
    return;
  }
  const str = value.replace(/\\\\/g, "\\").replaceAll('"', '\\"');
  return `"${str}"`;
};

/**
 * Parses a <color> value.
 *
 * @param {Array<object>} val - The AST value.
 * @returns {string|undefined} The parsed color.
 */
const parseColor = (val) => {
  const [item] = val;
  const { name, type, value } = item ?? {};
  switch (type) {
    case AST_TYPES.FUNCTION: {
      const res = resolveColor(`${name}(${value})`, {
        format: "specifiedValue"
      });
      if (res) {
        return res;
      }
      break;
    }
    case AST_TYPES.HASH: {
      const res = resolveColor(`#${value}`, {
        format: "specifiedValue"
      });
      if (res) {
        return res;
      }
      break;
    }
    case AST_TYPES.IDENTIFIER: {
      if (SYS_COLORS.has(name)) {
        return name;
      }
      const res = resolveColor(name, {
        format: "specifiedValue"
      });
      if (res) {
        return res;
      }
      break;
    }
    default:
  }
};

/**
 * Parses a <gradient> value.
 *
 * @param {Array<object>} val - The AST value.
 * @returns {string|undefined} The parsed gradient.
 */
const parseGradient = (val) => {
  const [item] = val;
  const { name, type, value } = item ?? {};
  if (type !== AST_TYPES.FUNCTION) {
    return;
  }
  const res = resolveGradient(`${name}(${value})`, {
    format: "specifiedValue"
  });
  if (res) {
    return res;
  }
};

/**
 * Resolves a keyword value.
 *
 * @param {Array<object>} value - The AST node array containing the keyword value.
 * @param {object} [opt={}] - The options for parsing.
 * @returns {string|undefined} The resolved keyword or undefined.
 */
const resolveKeywordValue = (value, opt = {}) => {
  const [{ name, type }] = value;
  const { length } = opt;
  switch (type) {
    case AST_TYPES.GLOBAL_KEYWORD: {
      if (length > 1) {
        return;
      }
      return name;
    }
    case AST_TYPES.IDENTIFIER: {
      return name;
    }
    default:
  }
};

/**
 * Resolves a function value.
 *
 * @param {Array<object>} value - The AST node array containing the function value.
 * @param {object} [opt={}] - The options for parsing.
 * @returns {string|undefined} The resolved function or undefined.
 */
const resolveFunctionValue = (value, opt = {}) => {
  const [{ name, type, value: itemValue }] = value;
  const { length } = opt;
  switch (type) {
    case AST_TYPES.FUNCTION: {
      return `${name}(${itemValue})`;
    }
    case AST_TYPES.GLOBAL_KEYWORD: {
      if (length > 1) {
        return;
      }
      return name;
    }
    case AST_TYPES.IDENTIFIER: {
      return name;
    }
    default:
  }
};

/**
 * Resolves a length or percentage or number value.
 *
 * @param {Array<object>} value - The AST node array containing the value.
 * @param {object} [opt={}] - The options for parsing.
 * @returns {string|undefined} The resolved length/percentage/number or undefined.
 */
const resolveNumericValue = (value, opt = {}) => {
  const [{ name, type: itemType, value: itemValue }] = value;
  const { length, type } = opt;
  switch (itemType) {
    case AST_TYPES.CALC: {
      return `${name}(${itemValue})`;
    }
    case AST_TYPES.DIMENSION: {
      if (type === "angle") {
        return parseAngle(value, opt);
      }
      return parseLength(value, opt);
    }
    case AST_TYPES.GLOBAL_KEYWORD: {
      if (length > 1) {
        return;
      }
      return name;
    }
    case AST_TYPES.IDENTIFIER: {
      return name;
    }
    case AST_TYPES.NUMBER: {
      switch (type) {
        case "angle": {
          return parseAngle(value, opt);
        }
        case "length": {
          return parseLength(value, opt);
        }
        case "percentage": {
          return parsePercentage(value, opt);
        }
        default: {
          return parseNumber(value, opt);
        }
      }
    }
    case AST_TYPES.PERCENTAGE: {
      return parsePercentage(value, opt);
    }
    default:
  }
};

/**
 * Resolves a color value.
 *
 * @param {Array<object>} value - The AST node array containing the color value.
 * @param {object} [opt={}] - The options for parsing.
 * @returns {string|undefined} The resolved color or undefined.
 */
const resolveColorValue = (value, opt = {}) => {
  const [{ name, type }] = value;
  const { length } = opt;
  switch (type) {
    case AST_TYPES.GLOBAL_KEYWORD: {
      if (length > 1) {
        return;
      }
      return name;
    }
    default: {
      return parseColor(value, opt);
    }
  }
};

/**
 * Resolves a gradient or URL value.
 *
 * @param {Array<object>} value - The AST node array containing the color value.
 * @param {object} [opt={}] - The options for parsing.
 * @returns {string|undefined} The resolved gradient/url or undefined.
 */
const resolveGradientUrlValue = (value, opt = {}) => {
  const [{ name, type }] = value;
  const { length } = opt;
  switch (type) {
    case AST_TYPES.GLOBAL_KEYWORD: {
      if (length > 1) {
        return;
      }
      return name;
    }
    case AST_TYPES.IDENTIFIER: {
      return name;
    }
    case AST_TYPES.URL: {
      return parseUrl(value, opt);
    }
    default: {
      return parseGradient(value, opt);
    }
  }
};

/**
 * Resolves a border shorthand value.
 *
 * @param {Array<object>} value - The AST node array containing the shorthand value.
 * @param {object} subProps - The sub properties object.
 * @param {Map} parsedValues - The Map of parsed values.
 * @returns {Array|string|undefined} - The resolved [prop, value] pair, keyword or undefined.
 */
const resolveBorderShorthandValue = (value, subProps, parsedValues) => {
  const [{ isNumber, name, type, value: itemValue }] = value;
  const { color: colorProp, style: styleProp, width: widthProp } = subProps;
  switch (type) {
    case AST_TYPES.CALC: {
      if (isNumber || parsedValues.has(widthProp)) {
        return;
      }
      return [widthProp, `${name}(${itemValue}`];
    }
    case AST_TYPES.DIMENSION:
    case AST_TYPES.NUMBER: {
      if (parsedValues.has(widthProp)) {
        return;
      }
      const parsedValue = parseLength(value, { min: 0 });
      if (!parsedValue) {
        return;
      }
      return [widthProp, parsedValue];
    }
    case AST_TYPES.FUNCTION:
    case AST_TYPES.HASH: {
      if (parsedValues.has(colorProp)) {
        return;
      }
      const parsedValue = parseColor(value);
      if (!parsedValue) {
        return;
      }
      return [colorProp, parsedValue];
    }
    case AST_TYPES.GLOBAL_KEYWORD: {
      return name;
    }
    case AST_TYPES.IDENTIFIER: {
      if (isValidPropertyValue(widthProp, name)) {
        if (parsedValues.has(widthProp)) {
          return;
        }
        return [widthProp, name];
      } else if (isValidPropertyValue(styleProp, name)) {
        if (parsedValues.has(styleProp)) {
          return;
        }
        return [styleProp, name];
      } else if (isValidPropertyValue(colorProp, name)) {
        if (parsedValues.has(colorProp)) {
          return;
        }
        return [colorProp, name];
      }
      break;
    }
    default:
  }
};

module.exports = {
  AST_TYPES,
  hasCalcFunc,
  hasVarFunc,
  isGlobalKeyword,
  isValidPropertyValue,
  parseAngle,
  parseCSS,
  parseColor,
  parseGradient,
  parseLength,
  parseNumber,
  parsePercentage,
  parsePropertyValue,
  parseString,
  parseUrl,
  prepareValue,
  resolveBorderShorthandValue,
  resolveCalc,
  resolveColorValue,
  resolveFunctionValue,
  resolveGradientUrlValue,
  resolveKeywordValue,
  resolveNumericValue,
  splitValue
};
