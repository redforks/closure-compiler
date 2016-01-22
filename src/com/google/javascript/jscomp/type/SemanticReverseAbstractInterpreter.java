/*
 * Copyright 2007 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp.type;

import static com.google.javascript.rhino.jstype.JSTypeNative.UNKNOWN_TYPE;

import com.google.common.base.Function;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.JSType.TypePair;
import com.google.javascript.rhino.jstype.JSTypeNative;
import com.google.javascript.rhino.jstype.JSTypeRegistry;
import com.google.javascript.rhino.jstype.ObjectType;
import com.google.javascript.rhino.jstype.StaticTypedSlot;
import com.google.javascript.rhino.jstype.UnionType;
import com.google.javascript.rhino.jstype.Visitor;

/**
 * A reverse abstract interpreter using the semantics of the JavaScript
 * language as a means to reverse interpret computations. This interpreter
 * expects the parse tree inputs to be typed.
 *
 */
public final class SemanticReverseAbstractInterpreter
    extends ChainableReverseAbstractInterpreter {

  /**
   * Merging function for equality between types.
   */
  private static final Function<TypePair, TypePair> EQ =
    new Function<TypePair, TypePair>() {
      @Override
      public TypePair apply(TypePair p) {
        if (p.typeA == null || p.typeB == null) {
          return null;
        }
        return p.typeA.getTypesUnderEquality(p.typeB);
      }
    };

  /**
   * Merging function for non-equality between types.
   */
  private static final Function<TypePair, TypePair> NE =
    new Function<TypePair, TypePair>() {
      @Override
      public TypePair apply(TypePair p) {
        if (p.typeA == null || p.typeB == null) {
          return null;
        }
        return p.typeA.getTypesUnderInequality(p.typeB);
      }
    };

  /**
   * Merging function for strict equality between types.
   */
  private static final
      Function<TypePair, TypePair> SHEQ =
    new Function<TypePair, TypePair>() {
      @Override
      public TypePair apply(TypePair p) {
        if (p.typeA == null || p.typeB == null) {
          return null;
        }
        return p.typeA.getTypesUnderShallowEquality(p.typeB);
      }
    };

  /**
   * Merging function for strict non-equality between types.
   */
  private static final
      Function<TypePair, TypePair> SHNE =
    new Function<TypePair, TypePair>() {
      @Override
      public TypePair apply(TypePair p) {
        if (p.typeA == null || p.typeB == null) {
          return null;
        }
        return p.typeA.getTypesUnderShallowInequality(p.typeB);
      }
    };

  /**
   * Merging function for inequality comparisons between types.
   */
  private final Function<TypePair, TypePair> ineq =
    new Function<TypePair, TypePair>() {
      @Override
      public TypePair apply(TypePair p) {
        return new TypePair(
            getRestrictedWithoutUndefined(p.typeA),
            getRestrictedWithoutUndefined(p.typeB));
      }
    };

  /**
   * Creates a semantic reverse abstract interpreter.
   */
  public SemanticReverseAbstractInterpreter(JSTypeRegistry typeRegistry) {
    super(typeRegistry);
  }

  @Override
  public FlowScope getPreciserScopeKnowingConditionOutcome(Node condition,
      FlowScope blindScope, boolean outcome) {
    // Check for the typeof operator.
    int operatorToken = condition.getType();
    switch (operatorToken) {
      case Token.EQ:
      case Token.NE:
      case Token.SHEQ:
      case Token.SHNE:
      case Token.CASE:
        Node left;
        Node right;
        if (operatorToken == Token.CASE) {
          left = condition.getParent().getFirstChild(); // the switch condition
          right = condition.getFirstChild();
        } else {
          left = condition.getFirstChild();
          right = condition.getLastChild();
        }

        Node typeOfNode = null;
        Node stringNode = null;
        if (left.isTypeOf() && right.isString()) {
          typeOfNode = left;
          stringNode = right;
        } else if (right.isTypeOf() &&
                   left.isString()) {
          typeOfNode = right;
          stringNode = left;
        }
        if (typeOfNode != null && stringNode != null) {
          Node operandNode = typeOfNode.getFirstChild();
          JSType operandType = getTypeIfRefinable(operandNode, blindScope);
          if (operandType != null) {
            boolean resultEqualsValue = operatorToken == Token.EQ ||
                operatorToken == Token.SHEQ || operatorToken == Token.CASE;
            if (!outcome) {
              resultEqualsValue = !resultEqualsValue;
            }
            return caseTypeOf(operandNode, operandType, stringNode.getString(),
                resultEqualsValue, blindScope);
          }
        }
    }
    switch (operatorToken) {
      case Token.AND:
        if (outcome) {
          return caseAndOrNotShortCircuiting(condition.getFirstChild(),
              condition.getLastChild(), blindScope, true);
        } else {
          return caseAndOrMaybeShortCircuiting(condition.getFirstChild(),
              condition.getLastChild(), blindScope, true);
        }

      case Token.OR:
        if (!outcome) {
          return caseAndOrNotShortCircuiting(condition.getFirstChild(),
              condition.getLastChild(), blindScope, false);
        } else {
          return caseAndOrMaybeShortCircuiting(condition.getFirstChild(),
              condition.getLastChild(), blindScope, false);
        }

      case Token.EQ:
        if (outcome) {
          return caseEquality(condition, blindScope, EQ);
        } else {
          return caseEquality(condition, blindScope, NE);
        }

      case Token.NE:
        if (outcome) {
          return caseEquality(condition, blindScope, NE);
        } else {
          return caseEquality(condition, blindScope, EQ);
        }

      case Token.SHEQ:
        if (outcome) {
          return caseEquality(condition, blindScope, SHEQ);
        } else {
          return caseEquality(condition, blindScope, SHNE);
        }

      case Token.SHNE:
        if (outcome) {
          return caseEquality(condition, blindScope, SHNE);
        } else {
          return caseEquality(condition, blindScope, SHEQ);
        }

      case Token.NAME:
      case Token.GETPROP:
        return caseNameOrGetProp(condition, blindScope, outcome);

      case Token.ASSIGN:
        return firstPreciserScopeKnowingConditionOutcome(
            condition.getFirstChild(),
            firstPreciserScopeKnowingConditionOutcome(
                condition.getSecondChild(), blindScope, outcome),
            outcome);

      case Token.NOT:
        return firstPreciserScopeKnowingConditionOutcome(
            condition.getFirstChild(), blindScope, !outcome);

      case Token.LE:
      case Token.LT:
      case Token.GE:
      case Token.GT:
        if (outcome) {
          return caseEquality(condition, blindScope, ineq);
        }
        break;

      case Token.INSTANCEOF:
        return caseInstanceOf(
            condition.getFirstChild(), condition.getLastChild(), blindScope,
            outcome);

      case Token.IN:
        if (outcome && condition.getFirstChild().isString()) {
          return caseIn(condition.getLastChild(),
              condition.getFirstChild().getString(), blindScope);
        }
        break;

      case Token.CASE:
        Node left =
            condition.getParent().getFirstChild(); // the switch condition
        Node right = condition.getFirstChild();
        if (outcome) {
          return caseEquality(left, right, blindScope, SHEQ);
        } else {
          return caseEquality(left, right, blindScope, SHNE);
        }
    }
    return nextPreciserScopeKnowingConditionOutcome(
        condition, blindScope, outcome);
  }

  private FlowScope caseEquality(Node condition, FlowScope blindScope,
      Function<TypePair, TypePair> merging) {
    return caseEquality(condition.getFirstChild(), condition.getLastChild(),
                        blindScope, merging);
  }

  private FlowScope caseEquality(Node left, Node right, FlowScope blindScope,
      Function<TypePair, TypePair> merging) {
    // left type
    JSType leftType = getTypeIfRefinable(left, blindScope);
    boolean leftIsRefineable;
    if (leftType != null) {
      leftIsRefineable = true;
    } else {
      leftIsRefineable = false;
      leftType = left.getJSType();
    }

    // right type
    JSType rightType = getTypeIfRefinable(right, blindScope);
    boolean rightIsRefineable;
    if (rightType != null) {
      rightIsRefineable = true;
    } else {
      rightIsRefineable = false;
      rightType = right.getJSType();
    }

    // merged types
    TypePair merged = merging.apply(new TypePair(leftType, rightType));

    // creating new scope
    if (merged != null) {
      return maybeRestrictTwoNames(
          blindScope,
          left, leftType, leftIsRefineable ? merged.typeA : null,
          right, rightType, rightIsRefineable ? merged.typeB : null);
    }
    return blindScope;
  }

  private FlowScope caseAndOrNotShortCircuiting(Node left, Node right,
      FlowScope blindScope, boolean outcome) {
    // left type
    JSType leftType = getTypeIfRefinable(left, blindScope);
    boolean leftIsRefineable;
    if (leftType != null) {
      leftIsRefineable = true;
    } else {
      leftIsRefineable = false;
      leftType = left.getJSType();
      blindScope = firstPreciserScopeKnowingConditionOutcome(
          left, blindScope, outcome);
    }

    // restricting left type
    JSType restrictedLeftType = (leftType == null) ? null :
        leftType.getRestrictedTypeGivenToBooleanOutcome(outcome);
    if (restrictedLeftType == null) {
      return firstPreciserScopeKnowingConditionOutcome(
          right, blindScope, outcome);
    }
    blindScope = maybeRestrictName(blindScope, left, leftType,
        leftIsRefineable ? restrictedLeftType : null);

    // right type
    JSType rightType = getTypeIfRefinable(right, blindScope);
    boolean rightIsRefineable;
    if (rightType != null) {
      rightIsRefineable = true;
    } else {
      rightIsRefineable = false;
      rightType = right.getJSType();
      blindScope = firstPreciserScopeKnowingConditionOutcome(
          right, blindScope, outcome);
    }

    if (outcome) {
      JSType restrictedRightType = (rightType == null) ? null :
          rightType.getRestrictedTypeGivenToBooleanOutcome(outcome);
      // creating new scope
      return maybeRestrictName(blindScope, right, rightType,
          rightIsRefineable ? restrictedRightType : null);
    }
    return blindScope;
  }

  private FlowScope caseAndOrMaybeShortCircuiting(Node left, Node right,
      FlowScope blindScope, boolean outcome) {
    FlowScope leftScope = firstPreciserScopeKnowingConditionOutcome(
        left, blindScope, !outcome);
    StaticTypedSlot<JSType> leftVar = leftScope.findUniqueRefinedSlot(blindScope);
    if (leftVar == null) {
      // If we did create a more precise scope, blindScope has a child and
      // it is frozen. We can't just throw it away to return it. So we
      // must create a child instead.
      return blindScope == leftScope ?
          blindScope : blindScope.createChildFlowScope();
    }
    FlowScope rightScope = firstPreciserScopeKnowingConditionOutcome(
        left, blindScope, outcome);
    rightScope = firstPreciserScopeKnowingConditionOutcome(
        right, rightScope, !outcome);
    StaticTypedSlot<JSType> rightVar = rightScope.findUniqueRefinedSlot(blindScope);
    if (rightVar == null || !leftVar.getName().equals(rightVar.getName())) {
      return blindScope == rightScope ?
          blindScope : blindScope.createChildFlowScope();
    }
    JSType type = leftVar.getType().getLeastSupertype(rightVar.getType());
    FlowScope informed = blindScope.createChildFlowScope();
    informed.inferSlotType(leftVar.getName(), type);
    return informed;
  }

  /**
   * If the restrictedType differs from the originalType, then we should
   * branch the current flow scope and create a new flow scope with the name
   * declared with the new type.
   *
   * We try not to create spurious child flow scopes as this makes type
   * inference slower.
   *
   * We also do not want spurious slots around in type inference, because
   * we use these as a signal for "checked unknown" types. A "checked unknown"
   * type is a symbol that the programmer has already checked and verified that
   * it's defined, even if we don't know what it is.
   *
   * It is OK to pass non-name nodes into this method, as long as you pass
   * in {@code null} for a restricted type.
   */
  private FlowScope maybeRestrictName(FlowScope blindScope, Node node,
      JSType originalType, JSType restrictedType) {
    if (restrictedType != null && restrictedType != originalType) {
      FlowScope informed = blindScope.createChildFlowScope();
      declareNameInScope(informed, node, restrictedType);
      return informed;
    }
    return blindScope;
  }

  /**
   * @see #maybeRestrictName
   */
  private FlowScope maybeRestrictTwoNames(
      FlowScope blindScope,
      Node left, JSType originalLeftType, JSType restrictedLeftType,
      Node right, JSType originalRightType, JSType restrictedRightType) {
    boolean shouldRefineLeft =
        restrictedLeftType != null && restrictedLeftType != originalLeftType;
    boolean shouldRefineRight =
        restrictedRightType != null && restrictedRightType != originalRightType;
    if (shouldRefineLeft || shouldRefineRight) {
      FlowScope informed = blindScope.createChildFlowScope();
      if (shouldRefineLeft) {
        declareNameInScope(informed, left, restrictedLeftType);
      }
      if (shouldRefineRight) {
        declareNameInScope(informed, right, restrictedRightType);
      }
      return informed;
    }
    return blindScope;
  }

  private FlowScope caseNameOrGetProp(Node name, FlowScope blindScope,
      boolean outcome) {
    JSType type = getTypeIfRefinable(name, blindScope);
    if (type != null) {
      return maybeRestrictName(
          blindScope, name, type,
          type.getRestrictedTypeGivenToBooleanOutcome(outcome));
    }
    return blindScope;
  }

  private FlowScope caseTypeOf(Node node, JSType type, String value,
        boolean resultEqualsValue, FlowScope blindScope) {
    return maybeRestrictName(
        blindScope, node, type,
        getRestrictedByTypeOfResult(type, value, resultEqualsValue));
  }

  private FlowScope caseInstanceOf(Node left, Node right, FlowScope blindScope,
      boolean outcome) {
    JSType leftType = getTypeIfRefinable(left, blindScope);
    if (leftType == null) {
      return blindScope;
    }
    JSType rightType = right.getJSType();
    ObjectType targetType =
        typeRegistry.getNativeObjectType(JSTypeNative.UNKNOWN_TYPE);
    if (rightType != null && rightType.isFunctionType()) {
      targetType = rightType.toMaybeFunctionType();
    }
    Visitor<JSType> visitor;
    if (outcome) {
      visitor = new RestrictByTrueInstanceOfResultVisitor(targetType);
    } else {
      visitor = new RestrictByFalseInstanceOfResultVisitor(targetType);
    }
    return maybeRestrictName(
        blindScope, left, leftType, leftType.visit(visitor));
  }

  /**
   * Given 'property in object', ensures that the object has the property in the
   * informed scope by defining it as a qualified name if the object type lacks
   * the property and it's not in the blind scope.
   * @param object The node of the right-side of the in.
   * @param propertyName The string of the left-side of the in.
   */
  private FlowScope caseIn(Node object, String propertyName, FlowScope blindScope) {
    JSType jsType = object.getJSType();
    jsType = this.getRestrictedWithoutNull(jsType);
    jsType = this.getRestrictedWithoutUndefined(jsType);

    boolean hasProperty = false;
    ObjectType objectType = ObjectType.cast(jsType);
    if (objectType != null) {
      hasProperty = objectType.hasProperty(propertyName);
    }
    if (!hasProperty) {
      String qualifiedName = object.getQualifiedName();
      if (qualifiedName != null) {
        String propertyQualifiedName = qualifiedName + "." + propertyName;
        if (blindScope.getSlot(propertyQualifiedName) == null) {
          FlowScope informed = blindScope.createChildFlowScope();
          JSType unknownType = typeRegistry.getNativeType(
              JSTypeNative.UNKNOWN_TYPE);
          informed.inferQualifiedSlot(
              object, propertyQualifiedName, unknownType, unknownType, false);
          return informed;
        }
      }
    }
    return blindScope;
  }

  /**
   * @see SemanticReverseAbstractInterpreter#caseInstanceOf
   */
  private class RestrictByTrueInstanceOfResultVisitor
      extends RestrictByTrueTypeOfResultVisitor {
    private final ObjectType target;

    RestrictByTrueInstanceOfResultVisitor(ObjectType target) {
      this.target = target;
    }

    @Override
    protected JSType caseTopType(JSType type) {
      return applyCommonRestriction(type);
    }

    @Override
    public JSType caseUnknownType() {
      FunctionType funcTarget = JSType.toMaybeFunctionType(target);
      if (funcTarget != null && funcTarget.hasInstanceType()) {
        return funcTarget.getInstanceType();
      }
      return getNativeType(UNKNOWN_TYPE);
    }

    @Override
    public JSType caseObjectType(ObjectType type) {
      return applyCommonRestriction(type);
    }

    @Override
    public JSType caseUnionType(UnionType type) {
      return applyCommonRestriction(type);
    }

    @Override
    public JSType caseFunctionType(FunctionType type) {
      return caseObjectType(type);
    }

    private JSType applyCommonRestriction(JSType type) {
      if (target.isUnknownType()) {
        return type;
      }

      FunctionType funcTarget = target.toMaybeFunctionType();
      if (funcTarget.hasInstanceType()) {
        return type.getGreatestSubtype(funcTarget.getInstanceType());
      }

      return null;
    }
  }

  /**
   * @see SemanticReverseAbstractInterpreter#caseInstanceOf
   */
  private class RestrictByFalseInstanceOfResultVisitor
      extends RestrictByFalseTypeOfResultVisitor {
    private final ObjectType target;

    RestrictByFalseInstanceOfResultVisitor(ObjectType target) {
      this.target = target;
    }

    @Override
    public JSType caseObjectType(ObjectType type) {
      if (target.isUnknownType()) {
        return type;
      }

      FunctionType funcTarget = target.toMaybeFunctionType();
      if (funcTarget.hasInstanceType()) {
        if (type.isSubtype(funcTarget.getInstanceType())) {
          return null;
        }

        return type;
      }

      return null;
    }

    @Override
    public JSType caseUnionType(UnionType type) {
      if (target.isUnknownType()) {
        return type;
      }

      FunctionType funcTarget = target.toMaybeFunctionType();
      if (funcTarget.hasInstanceType()) {
        return type.getRestrictedUnion(funcTarget.getInstanceType());
      }

      return null;
    }

    @Override
    public JSType caseFunctionType(FunctionType type) {
      return caseObjectType(type);
    }
  }
}
