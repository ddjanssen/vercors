package vct.antlr4.parser

import org.antlr.v4.runtime.tree.TerminalNode
import org.antlr.v4.runtime.{CommonToken, CommonTokenStream, ParserRuleContext}
import vct.antlr4.generated.JavaParser
import vct.antlr4.generated.JavaParser._
import vct.antlr4.generated.JavaParserPatterns._
import vct.col.ast.`type`.{ASTReserved, ClassType, PrimitiveSort, Type}
import vct.col.ast.expr.StandardOperator._
import vct.col.ast.expr.{MethodInvokation, NameExpression, StandardOperator}
import vct.col.ast.generic.ASTNode
import vct.col.ast.stmt.composite.BlockStatement
import vct.col.ast.stmt.decl.{ASTClass, ASTDeclaration, ASTSpecial, DeclarationStatement, Method, NameSpace, ProgramUnit}
import vct.col.ast.util.ContractBuilder

import scala.collection.JavaConverters._

object JavaJMLtoCOL {
  def convert(tree: CompilationUnitContext, fileName: String, tokens: CommonTokenStream, parser: JavaParser): ProgramUnit = {
    JavaJMLtoCOL(fileName, tokens, parser).convertUnit(tree)
  }
}

case class JavaJMLtoCOL(fileName: String, tokens: CommonTokenStream, parser: JavaParser)
  extends ToCOL(fileName, tokens, parser) {
  def convertUnit(tree: CompilationUnitContext): ProgramUnit = tree match {
    case CompilationUnit0(maybePackage, imports, decls, _) =>
      val result = new ProgramUnit()
      val namespace = maybePackage match {
        case None => create namespace(NameSpace.NONAME)
        case Some(PackageDeclaration0(Seq(annotation, _*), _, _, _)) =>
          ??(annotation) // package declaration annotations are not supported.
        case Some(PackageDeclaration0(Seq(), "package", name, _)) =>
          create namespace(convertQualifiedName(name):_*)
      }
      imports.foreach {
        case ImportDeclaration0("import", static, name, importAll, _) =>
          namespace.add_import(static.isDefined, importAll.isDefined, convertQualifiedName(name):_*)
      }
      decls.foreach(convertDecl(_).foreach(namespace.add(_)))
      result.add(namespace)
      result
  }

  def convertQualifiedName(name: QualifiedNameContext): Seq[String] = name match {
    case QualifiedName0(id) => Seq(convertID(id))
    case QualifiedName1(id, _, ids) => convertID(id) +: convertQualifiedName(ids)
  }

  def convertID(id: ParserRuleContext): String = id match {
    case JavaIdentifier0(ExtraIdentifier0(id)) =>
      fail(id, "This identifier is reserved and cannot be declared.")
    case JavaIdentifier1(s) => s
    case Identifier0(id) => convertID(id)

    case EnumConstantName0(id) => convertID(id)
  }

  def convertIDName(id: ParserRuleContext): NameExpression = origin(id, id match {
    case JavaIdentifier0(ExtraIdentifier0(id)) =>
      convertValReserved(id)
    case JavaIdentifier1(s) => create unresolved_name s
    case Identifier0(id) => convertIDName(id)
  })

  def convertDecl(decl: ParserRuleContext): Seq[ASTDeclaration] = origin(decl, decl match {
    case TypeDeclaration0(mods, classDecl) =>
      val cls = convertClass(classDecl)
      mods.map(convertModifier).foreach(mod => cls.attach(mod))
      Seq(cls)
    case TypeDeclaration1(mods, enumDecl) =>
      ??(enumDecl)
    case TypeDeclaration2(mods, interfaceDecl) =>
      ??(interfaceDecl)
    case TypeDeclaration3(mods, annotationDecl) =>
      ??(annotationDecl)
    case TypeDeclaration4(_extraSemicolon) => Seq()

    case ClassBodyDeclaration0(_extraSemicolon) => Seq()
    case ClassBodyDeclaration1(maybeStatic, block) =>
      // This is a block that is executed on class load if "static" is present, otherwise
      // the code block is executed on every instance creation (prior to constructors, I think)
      ??(decl)
    case ClassBodyDeclaration2(maybeContract, mods, member) =>
      val decls = convertDecl(member)
      val contract = getContract(maybeContract, convertValContract)
      decls.foreach(decl => {
        mods.map(convertModifier).foreach(mod => decl.attach(mod))
        decl match {
          case method: Method => method.setContract(contract)
          case other if maybeContract.isDefined =>
            fail(maybeContract.get, "Cannot attach contract to member below")
          case other => // OK
        }
      })
      decls
    case InterfaceBodyDeclaration0(mods, member) =>
      val decls = convertDecl(member)
      decls.foreach(decl => mods.map(convertModifier).foreach(mod => decl.attach(mod)))
      decls

    case MemberDeclaration0(fwd) => convertDecl(fwd)
    case MemberDeclaration1(fwd) => convertDecl(fwd)
    case MemberDeclaration2(fwd) => convertDecl(fwd)
    case MemberDeclaration3(fwd) => convertDecl(fwd)
    case MemberDeclaration4(fwd) => convertDecl(fwd)
    case MemberDeclaration5(fwd) => convertDecl(fwd)
    case MemberDeclaration6(fwd) => convertDecl(fwd)
    case MemberDeclaration7(fwd) => convertDecl(fwd)
    case MemberDeclaration8(fwd) => convertDecl(fwd)
    case MemberDeclaration9(fwd) => convertDecl(fwd)

    case MethodDeclaration0(_, _, _, _, Some(throws), _) =>
      ??(throws) // exceptions are unsupported
    case MethodDeclaration0(retType, name, paramsNode, maybeDims, None, maybeBody) =>
      val dims = maybeDims match { case None => 0; case Some(Dims0(dims)) => dims.size }
      val returns = convertType(retType, dims)
      val (params, varargs) = convertParams(paramsNode)
      val body = maybeBody match {
        case MethodBodyOrEmpty0(";") => None
        case MethodBodyOrEmpty1(MethodBody0(block)) => Some(convertBlock(block))
      }
      Seq(create method_decl(returns, null, convertID(name), params.toArray, body.orNull))
    case GenericMethodDeclaration0(typeParams, methodDecl) =>
      ??(typeParams) //generics are unsupported
    case InterfaceMethodDeclaration0(retType, name, params, maybeDims, None, _) =>
      ??(decl) // inheritance is unsupported
    case GenericInterfaceMethodDeclaration0(typeParams, methodDecl) =>
      ??(typeParams) // generics are unsupported
    case ConstructorDeclaration0(clsName, paramsNode, maybeThrows, bodyNode) =>
      val returns = create primitive_type PrimitiveSort.Void
      val (params, varargs) = convertParams(paramsNode)
      val body = bodyNode match {
        case ConstructorBody0(block) => convertBlock(block)
      }
      Seq(create method_kind(Method.Kind.Constructor, returns, null, convertID(clsName), params.toArray, varargs, body))
    case GenericConstructorDeclaration0(typeParams, constructorDecl) =>
      ??(typeParams) // generics are unsupported

    case FieldDeclaration0(t, declarators, _) =>
      for((name, dims, init) <- convertDeclarators(declarators))
        yield create field_decl(name, convertType(t, dims), init.orNull)
    case ConstDeclaration0(baseType, decls, _) =>
      for((name, dims, init) <- convertDeclarators(decls))
        yield create field_decl(name, convertType(baseType, dims), init.orNull)
    case LocalVariableDeclaration0(modsNode, t, decls) =>
      val mods = modsNode.map(convertModifier)
      convertDeclarators(decls).map { case (name, dims, init) =>
        val res = create field_decl(name, convertType(t, dims), init.orNull);
        mods.foreach(mod => res.attach(mod))
        res
      }
    case ExtraDeclaration1(AxiomDeclaration0("axiom", name, "{", left, "==", right, "}")) =>
      Seq(create axiom(name, create expression(EQ, expr(left), expr(right))))
  })

  def convertModifier(modifier: ParserRuleContext): NameExpression = origin(modifier, modifier match {
    case Modifier0(mod) => convertModifier(mod)
    case Modifier1(ExtraAnnotation0("pure")) =>
      create reserved_name(ASTReserved.Pure)
    case Modifier1(ExtraAnnotation1("inline")) =>
      create reserved_name(ASTReserved.Inline)
    case Modifier1(ExtraAnnotation2("thread_local")) =>
      create reserved_name(ASTReserved.ThreadLocal)
    case Modifier2(mod) => mod match {
      case "native" => ??(modifier)
      case "synchronized" => create reserved_name(ASTReserved.Synchronized)
      case "transient" => ??(modifier)
      case "volatile" => create reserved_name(ASTReserved.Volatile)
    }
    case ClassOrInterfaceModifier0(annotation) =>
      ??(annotation)
    case ClassOrInterfaceModifier1(attribute) =>
      create reserved_name(attribute match {
        case "public" => ASTReserved.Public
        case "protected" => ASTReserved.Protected
        case "private" => ASTReserved.Private
        case "static" => ASTReserved.Static
        case "abstract" => ASTReserved.Abstract
        case "final" => ASTReserved.Final
        case "strictfp" =>
          ??(modifier) // strict floating point math; unsupported.
      })
    case VariableModifier0("final") =>
      create reserved_name ASTReserved.Final
    case VariableModifier1(annotation) =>
      ??(annotation)
  })

  def convertClass(decl: ClassDeclarationContext): ASTClass = origin(decl, decl match {
    case ClassDeclaration0(_, _, Some(typeParams), _, _, _) =>
      ??(typeParams) // generics are not supported.
    case ClassDeclaration0("class", name, None, maybeExtends, maybeImplements, ClassBody0(_, decls, _)) =>
      val ext = maybeExtends match {
        case None => Seq()
        case Some(Ext0(_, t)) => Seq(convertType(t) match {
          case t: ClassType => t
          case _ => ??(t)
            // The ast does not allow bases that are not of ClassType, but the grammar does
        })
      }
      val imp = maybeImplements match {
        case None => Seq()
        case Some(Imp0(_, typeList)) => convertTypeList(typeList).map {
          case t: ClassType => t
            case _ => ??(typeList) // see above
        }
      }
      val cls = create ast_class(convertID(name), ASTClass.ClassKind.Plain, Array(), ext.toArray, imp.toArray)
      decls.map(convertDecl).foreach(_.foreach(cls.add))
      cls
  })

  def convertTypeList(tree: ParserRuleContext): Seq[Type] = tree match {
    case TypeList0(x) => Seq(convertType(x))
    case TypeList1(x, _, xs) => convertType(x) +: convertTypeList(xs)
    case TypeArguments0("<", xs, ">") => convertTypeList(xs)
    case TypeArgumentList0(x) => Seq(convertType(x))
    case TypeArgumentList1(x, _, xs) => convertType(x) +: convertTypeList(xs)
  }

  def convertType(tree: ParserRuleContext, extraDims: Int): Type = {
    var t = convertType(tree)
    for(_ <- 0 until extraDims)
      t = create.primitive_type(PrimitiveSort.Option,
            create.primitive_type(PrimitiveSort.Array,
              create.primitive_type(PrimitiveSort.Cell, t)))
    t
  }

  def convertType(tree: ParserRuleContext): Type = origin(tree, tree match {
    case TypeOrVoid0("void") => create primitive_type PrimitiveSort.Void
    case TypeOrVoid1(t) => convertType(t)

    case Type0(t, dims) =>
      convertType(t, dims match { case None => 0; case Some(Dims0(dims)) => dims.size })
    case Type1(t, dims) =>
      convertType(t, dims match { case None => 0; case Some(Dims0(dims)) => dims.size })
    case Type2(ExtraType0(name)) =>
      create primitive_type(name match {
        case "resource" => PrimitiveSort.Resource
        case "process" => PrimitiveSort.Process
        case "frac" => PrimitiveSort.Fraction
        case "zfrac" => PrimitiveSort.ZFraction
        case "rational" => PrimitiveSort.Rational
      })

    case PrimitiveType0(name) =>
      create primitive_type(name match {
        case "boolean" => PrimitiveSort.Boolean
        case "char" => PrimitiveSort.Char
        case "byte" => PrimitiveSort.Byte
        case "short" => PrimitiveSort.Short
        case "int" => PrimitiveSort.Integer
        case "long" => PrimitiveSort.Long
        case "float" => PrimitiveSort.Float
        case "double" => PrimitiveSort.Double
      })

    case ClassOrInterfaceType0(id, generics) =>
      val args = generics.map(convertTypeList).getOrElse(Seq())
      val name = convertID(id)
      if(name.equals("seq")) {
        args match {
          case Seq(subType) => create primitive_type(PrimitiveSort.Sequence, subType)
          case _ => ??(tree)
        }
      } else {
        create class_type(name, args:_*)
      }

    case ClassOrInterfaceType1(baseType, _, innerType, generics) =>
      ??(innerType) // inner classes are unsupported

    case TypeArgument0(t) => convertType(t)
    case wildcard: TypeArgument1Context => ??(wildcard)
  })

  def convertParams(params: ParserRuleContext): (Seq[DeclarationStatement], Boolean) = params match {
    case FormalParameters0(_, None, _) => (Seq(), false)
    case FormalParameters0(_, Some(list), _) => convertParams(list)
    case FormalParameterList0(varargsParam) => (Seq(convertParam(varargsParam)), true)
    case FormalParameterList1(list) => convertParams(list)
    case FormalParameterList2(list, _, varargsParam) => (convertParams(list)._1 :+ convertParam(varargsParam), true)
    case InitFormalParameterList0(param) => (Seq(convertParam(param)), false)
    case InitFormalParameterList1(param, _, list) => (convertParam(param) +: convertParams(list)._1, false)
  }

  def convertParam(param: ParserRuleContext): DeclarationStatement = origin(param, param match {
    case FormalParameter0(Seq(mod, _*), _, _) =>
      ??(mod) // modifiers to method arguments are unsupported
    case FormalParameter0(Seq(), t, declaratorName) =>
      val (name, extraDims) = convertDeclaratorName(declaratorName)
      create field_decl(name, convertType(t, extraDims))
  })

  def convertDeclaratorName(decl: VariableDeclaratorIdContext): (String, Int) = decl match {
    case VariableDeclaratorId0(name, None) => (convertID(name), 0)
    case VariableDeclaratorId0(name, Some(Dims0(dims))) => (convertID(name), dims.size)
  }

  def convertDeclarators(decls: ParserRuleContext): Seq[(String, Int, Option[ASTNode])] = decls match {
    case VariableDeclarators0(x) => Seq(convertDeclarator(x))
    case VariableDeclarators1(x, ",", xs) => convertDeclarator(x) +: convertDeclarators(xs)
    case ConstantDeclaratorList0(x) => Seq(convertDeclarator(x))
    case ConstantDeclaratorList1(x, ",", xs) => convertDeclarator(x) +: convertDeclarators(xs)
  }

  def convertDeclarator(decl: ParserRuleContext): (String, Int, Option[ASTNode]) = decl match {
    case VariableDeclarator0(declId, maybeInit) =>
      val (name, extraDims) = convertDeclaratorName(declId)
      (name, extraDims, maybeInit.map(expr))
    case ConstantDeclarator0(name, maybeDims, _, init) =>
      val extraDims = maybeDims.map { case Dims0(dims) => dims.size }.getOrElse(0)
      (convertID(name), extraDims, Some(expr(init)))
  }

  def convertBlock(block: BlockContext): BlockStatement = origin(block, block match {
    case Block0(_, stats, _) =>
      create block(stats.flatMap(convertStat):_*)
  })

  def convertStat(stat: ParserRuleContext): Seq[ASTNode] = origin(stat, stat match {
    // Statement that occurs in a block
    case BlockStatement0(LocalVariableDeclarationStatement0(varDecl, _)) =>
      convertDecl(varDecl)
    case BlockStatement1(stat) =>
      convertStat(stat)
    case BlockStatement2(typeDecl) =>
      convertDecl(typeDecl)

    case Statement0(block) =>
      Seq(convertBlock(block))
    case Statement1(_assert, exp, _message, _) =>
      Seq(create special(ASTSpecial.Kind.Assert, expr(exp)))
    case Statement2("if", cond, whenTrue, maybeWhenFalse) =>
      Seq(create ifthenelse(expr(cond),
        create block(convertStat(whenTrue):_*),
        maybeWhenFalse.map(stat => create block(convertStat(stat):_*)).orNull))
    case Statement3(maybeContract, "for", "(", ForControl0(forEachControl), ")", body) =>
      ??(forEachControl) // for(a : b) is unsupported
    case Statement3(maybeContract, "for", "(", ForControl1(maybeInit, _, maybeCond, _, maybeUpdate), ")", body) =>
      val contract = getContract(maybeContract, convertValContract)
      val loop = create for_loop(
        maybeInit.map(stat => create block(convertStat(stat):_*)).orNull,
        maybeCond.map(expr).orNull,
        maybeUpdate.map(stat => create block(convertStat(stat):_*)).orNull,
        create block(convertStat(body):_*)
      )
      loop.setContract(contract)
      Seq(loop)
    case Statement4(maybeContract, "while", cond, body) =>
      val contract = getContract(maybeContract, convertValContract)
      val loop = create while_loop(expr(cond), create block(convertStat(body):_*))
      loop.setContract(contract)
      Seq(loop)
    case Statement5("do", body, "while", cond, _) =>
      ??(stat) // do-while unsupported
    case _: Statement6Context =>
      ??(stat) // try-catch unsupported
    case _: Statement7Context =>
      ??(stat) // try-with-catch unuspported
    case Statement8("switch", obj, "{", caseStatMappings, extraCases, "}") =>
      ??(stat) // switch unsupported
    case Statement9("synchronized", obj, body) =>
      ??(stat) // synchronizing on objects unsupported
    case Statement10("return", maybeValue, _) =>
      Seq(maybeValue match {
        case None => create return_statement()
        case Some(value) => create return_statement(expr(value))
      })
    case Statement11("throw", exc, _) =>
      ??(stat) // exceptions unsupported
    case Statement12("break", maybeLabel, _) =>
      Seq(maybeLabel match {
        case None => create special(ASTSpecial.Kind.Break)
        case Some(lbl) => create special(ASTSpecial.Kind.Break, convertIDName(lbl))
      })
    case Statement13("continue", maybeLabel, _) =>
      Seq(maybeLabel match {
        case None => create special(ASTSpecial.Kind.Continue)
        case Some(lbl) => create special(ASTSpecial.Kind.Continue, convertIDName(lbl))
      })
    case Statement14(";") =>
      Seq() // nop
    case Statement15(exp, _) =>
      Seq(expr(exp))
    case Statement16(label, ":", stat) =>
      val res = convertStat(stat)
      res.foreach(_.addLabel(convertIDName(label)))
      res
    case Statement17(valStatement) =>
      convertValStat(valStatement)

    case ForInit0(varDecl) => convertDecl(varDecl)
    case ForInit1(exps) => exprList(exps)
    case ForUpdate0(exps) => exprList(exps)
  })

  def exprList(tree: ParserRuleContext): Seq[ASTNode] = tree match {
    case Arguments0(_, None, _) => Seq()
    case Arguments0(_, Some(xs), _) => exprList(xs)
    case ExpressionList0(exp) => Seq(expr(exp))
    case ExpressionList1(x, _, xs) => expr(x) +: exprList(xs)
  }

  def expr(tree: ParserRuleContext): ASTNode = origin(tree, tree match {
    case ParExpression0("(", exp, ")") => expr(exp)
    case StatementExpression0(exp) => expr(exp)
    case ConstantExpression0(exp) => expr(exp)

    case Expression0(primary) => expr(primary)
    case Expression1(obj, ".", field) =>
      create dereference(expr(obj), convertID(field))
    case Expression2(obj, ".", "this") => ??(tree)
    case Expression3(obj, ".", "new", typeArgs, creator) => ??(tree)
    case Expression4(obj, ".", "super", suffix) => ??(tree)
    case Expression5(obj, ".", invokation) =>
      expr(invokation) match {
        case call: MethodInvokation if call.`object` == null =>
          create invokation(expr(obj), null, call.method, call.getArgs:_*)
        case _ => ??(tree)
      }
    case Expression6(seq, "[", idx, "]") =>
      create expression(Subscript, expr(seq), expr(idx))
    case Expression7(_, _, _, _) => ??(tree) //arrow type
    case Expression8(_, Some(predicateLoc), _) => ??(tree)
    case Expression8(obj, None, argsNode) =>
      val args = exprList(argsNode)
      expr(obj) match {
        case name: NameExpression =>
          create invokation(null, null, name.getName, args:_*)
        case _ => ??(tree)
      }
    case Expression9("new", creator) => ??(tree)
    case Expression10("(", t, ")", exp) => ??(tree)
    case Expression11(exp, "++") => create expression(PostIncr, expr(exp))
    case Expression11(exp, "--") => create expression(PostDecr, expr(exp))
    case Expression12("+", exp) => expr(exp)
    case Expression12("-", exp) => create expression(UMinus, expr(exp))
    case Expression12("++", exp) => create expression(PreIncr, expr(exp))
    case Expression12("--", exp) => create expression(PreDecr, expr(exp))
    case Expression13("~", exp) => create expression(BitNot, expr(exp))
    case Expression13("!", exp) => create expression(Not, expr(exp))
    case Expression14(left, "*", right) => create expression(Mult, expr(left), expr(right))
    case Expression14(left, "/", right) => create expression(FloorDiv, expr(left), expr(right))
    case Expression14(left, "\\", right) => create expression(Div, expr(left), expr(right))
    case Expression14(left, "%", right) => create expression(Mod, expr(left), expr(right))
    case Expression15(left, "+", right) => create expression(Plus, expr(left), expr(right))
    case Expression15(left, "-", right) => create expression(Minus, expr(left), expr(right))
    case shiftExpr: Expression16Context =>
      val left = shiftExpr.children.get(0).asInstanceOf[ParserRuleContext]
      val right = shiftExpr.children.get(shiftExpr.children.size() - 1).asInstanceOf[ParserRuleContext]
      if(shiftExpr.children.size() == 5) { // >>>
        create expression(UnsignedRightShift, expr(left), expr(right))
      } else if(shiftExpr.children.get(1).asInstanceOf[TerminalNode].getText == "<") { // <<
        create expression(LeftShift, expr(left), expr(right))
      } else {
        create expression(RightShift, expr(left), expr(right))
      }
    case Expression17(left, comp, right) =>
      create expression(comp match {
        case "<" => StandardOperator.LT
        case "<=" => LTE
        case ">=" => GTE
        case ">" => StandardOperator.GT
      }, expr(left), expr(right))
    case Expression18(obj, "instanceof", t) =>
      create expression(Instance, expr(obj), convertType(t))
    case Expression19(left, "==", right) =>
      create expression(EQ, expr(left), expr(right))
    case Expression19(left, "!=", right) =>
      create expression(NEQ, expr(left), expr(right))
    case Expression20(left, "&", right) =>
      create expression(BitAnd, expr(left), expr(right))
    case Expression21(left, "^", right) =>
      create expression(BitXor, expr(left), expr(right))
    case Expression22(left, "|", right) =>
      create expression(BitOr, expr(left), expr(right))
    case Expression23(left, "&&", right) =>
      create expression(And, expr(left), expr(right))
    case Expression23(left, "**", right) =>
      create expression(Star, expr(left), expr(right))
    case Expression24(left, "||", right) =>
      create expression(Or, expr(left), expr(right))
    case Expression25(left, "==>", right) =>
      create expression(Implies, expr(left), expr(right))
    case Expression25(left, "-*", right) =>
      create expression(Wand, expr(left), expr(right))
    case Expression26(cond, "?", t, ":", f) =>
      create expression(ITE, expr(cond), expr(t), expr(f))
    case assignment: Expression27Context => assignment.children.asScala.toSeq match {
      case Seq(left: ExpressionContext, op, right: ExpressionContext) =>
        create assignment(expr(left), expr(right))
      case _ =>
        ??(assignment)
    }

    case Primary0("(", exp, ")") => expr(exp)
    case Primary1("this") => create reserved_name ASTReserved.This
    case Primary2("super") => create reserved_name ASTReserved.Super
    case Primary3(Literal0(s)) => create constant Integer.parseInt(s)
    case Primary3(Literal1(s)) => ??(tree) // float
    case Primary3(Literal2(s)) => ??(tree) // character
    case Primary3(Literal3(s)) => ??(tree) // string
    case Primary3(Literal4(s)) => create constant s.equals("true")
    case Primary3(Literal5("null")) => create reserved_name(ASTReserved.Null)
    case Primary4(name) => convertIDName(name)
    case Primary5(t, ".", "class") => ??(tree)
    case Primary6("void", ".", "class") => ??(tree)
    case _: Primary7Context => ??(tree) // generic invocation?
    case Primary8(extra) => expr(extra)

    case ExtraPrimary0(label, ":", exp) =>
      val res = expr(exp)
      res.addLabel(create unresolved_name label)
      res
    case ExtraPrimary1(valPrimary) => valExpr(valPrimary)

    case VariableDeclaratorInit0(_, exp) => expr(exp)
    case VariableInitializer0(arr) => ??(arr)
    case VariableInitializer1(exp) => expr(exp)
  })

  /* === Start of duplicated code block ===
   * Below here are the conversion methods for specification constructs. Because they are generated via a language-
   * specific parser, each language has a different set of classes for the ANTLR nodes of specifications. They are
   * however named identically, so we choose to keep this block of code textually the same across the different
   * languages.
   *
   * If you change anything here, please propagate the change to:
   *  - PVLtoCOL.scala
   *  - JavaJMLtoCOL.scala
   *  - CMLtoCOL.scala
   */
  def convertValExpList(args: ValExpressionListContext): Seq[ASTNode] = args match {
    case ValExpressionList0(exp) =>
      Seq(expr(exp))
    case ValExpressionList1(exp, ",", expList) =>
      expr(exp) +: convertValExpList(expList)
  }

  def convertValLabelList(args: ValLabelListContext): Seq[ASTNode] = args match {
    case ValLabelList0(label) =>
      Seq(create label(convertID(label)))
    case ValLabelList1(label, _, labels) =>
      (create label convertID(label)) +: convertValLabelList(labels)
  }

  def convertValClause(clause: ValContractClauseContext,
                       builder: ContractBuilder): Unit = clause match {
    case ValContractClause0(_modifies, names, _) =>
      builder.modifies(convertValExpList(names):_*)
    case ValContractClause1(_accessible, names, _) =>
      builder.accesses(convertValExpList(names):_*)
    case ValContractClause2(_requires, exp, _) =>
      builder.requires(expr(exp))
    case ValContractClause3(_ensures, exp, _) =>
      builder.ensures(expr(exp))
    case ValContractClause4(_given, t, name, _) =>
      builder.`given`(create.field_decl(convertID(name), convertType(t)))
    case ValContractClause5(_yields, t, name, _) =>
      builder.yields(create.field_decl(convertID(name), convertType(t)))
    case ValContractClause6(_context_everywhere, exp, _) =>
      builder.appendInvariant(expr(exp))
    case ValContractClause7(_context, exp, _) =>
      builder.context(expr(exp))
    case ValContractClause8(_loop_invariant, exp, _) =>
      builder.appendInvariant(expr(exp))
  }

  def convertValStat(stat: ValEmbedStatementBlockContext): Seq[ASTNode] = origin(stat, stat match {
    case ValEmbedStatementBlock0(_startSpec, stats, _endSpec) =>
      stats.map(convertValStat)
  })

  def convertValStat(stat: ValStatementContext): ASTNode = origin(stat, stat match {
    case ValStatement0(_create, block) =>
      create lemma(convertBlock(block))
    case ValStatement1(_qed, exp, _) =>
      create special(ASTSpecial.Kind.QED, expr(exp))
    case ValStatement2(_apply, exp, _) =>
      create special(ASTSpecial.Kind.Apply, expr(exp))
    case ValStatement3(_use, exp, _) =>
      create special(ASTSpecial.Kind.Use, expr(exp))
    case ValStatement4(_create, hist, _) =>
      create special(ASTSpecial.Kind.CreateHistory, expr(hist))
    case ValStatement5(_create, fut, _, proc, _) =>
      create special(ASTSpecial.Kind.CreateFuture, expr(fut), expr(proc))
    case ValStatement6(_destroy, hist, _, proc, _) =>
      create special(ASTSpecial.Kind.DestroyHistory, expr(hist), expr(proc))
    case ValStatement7(_destroy, fut, _) =>
      create special(ASTSpecial.Kind.DestroyFuture, expr(fut))
    case ValStatement8(_split, fut, _, perm1, _, proc1, _, perm2, _, proc2, _) =>
      create special(ASTSpecial.Kind.SplitHistory, expr(fut), expr(perm1), expr(proc1), expr(perm2), expr(proc2))
    case ValStatement9(_merge, fut, _, perm1, _, proc1, _, perm2, _, proc2, _) =>
      create special(ASTSpecial.Kind.MergeHistory, expr(fut), expr(perm1), expr(proc1), expr(perm2), expr(proc2))
    case ValStatement10(_choose, fut, _, perm, _, proc1, _, proc2, _) =>
      create special(ASTSpecial.Kind.ChooseHistory, expr(fut), expr(perm), expr(proc1), expr(proc2))
    case ValStatement11(_fold, pred, _) =>
      create special(ASTSpecial.Kind.Fold, expr(pred))
    case ValStatement12(_unfold, pred, _) =>
      create special(ASTSpecial.Kind.Unfold, expr(pred))
    case ValStatement13(_open, pred, _) =>
      create special(ASTSpecial.Kind.Open, expr(pred))
    case ValStatement14(_close, pred, _) =>
      create special(ASTSpecial.Kind.Close, expr(pred))
    case ValStatement15(_assert, assn, _) =>
      create special(ASTSpecial.Kind.Assert, expr(assn))
    case ValStatement16(_assume, assn, _) =>
      create special(ASTSpecial.Kind.Assume, expr(assn))
    case ValStatement17(_inhale, res, _) =>
      create special(ASTSpecial.Kind.Inhale, expr(res))
    case ValStatement18(_exhale, res, _) =>
      create special(ASTSpecial.Kind.Exhale, expr(res))
    case ValStatement19(_label, lbl, _) =>
      create special(ASTSpecial.Kind.Label, convertIDName(lbl))
    case ValStatement20(_refute, assn, _) =>
      create special(ASTSpecial.Kind.Refute, expr(assn))
    case ValStatement21(_witness, pred, _) =>
      create special(ASTSpecial.Kind.Witness, expr(pred))
    case ValStatement22(_ghost, code) =>
      ??(stat)
    case ValStatement23(_send, res, _to, lbl, _, thing, _) =>
      create special(ASTSpecial.Kind.Send, expr(res), create unresolved_name lbl, expr(thing))
    case ValStatement24(_recv, res, _from, lbl, _, thing, _) =>
      create special(ASTSpecial.Kind.Recv, expr(res), create unresolved_name(lbl), expr(thing))
    case ValStatement25(_transfer, exp, _) =>
      ??(stat)
    case ValStatement26(_csl_subject, obj, _) =>
      create special(ASTSpecial.Kind.CSLSubject, expr(obj))
    case ValStatement27(_spec_ignore, "}") =>
      create special ASTSpecial.Kind.SpecIgnoreEnd
    case ValStatement28(_spec_ignore, "{") =>
      create special ASTSpecial.Kind.SpecIgnoreStart
    case action: ValStatement29Context =>
      ??(action)
    case ValStatement30(_atomic, _, resList, _, block) =>
      create csl_atomic(convertBlock(block), resList.map(convertValLabelList).getOrElse(Seq()):_*)
  })

  def valExpr(exp: ValPrimaryContext): ASTNode = origin(exp, exp match {
    case ValPrimary0(t, "{", maybeExps, "}") =>
      val exps = maybeExps.map(convertValExpList).getOrElse(Seq())
      create struct_value(convertType(t), null, exps:_*)
    case ValPrimary1("[", factor, "]", exp) =>
      create expression(Scale, expr(factor), expr(exp))
    case ValPrimary2("|", seq, "|") =>
      create expression(Size, expr(seq))
    case ValPrimary3("\\unfolding", pred, "\\in", exp) =>
      create expression(Unfolding, expr(pred), expr(exp))
    case ValPrimary4("(", exp, "!", indepOf, ")") =>
      create expression(IndependentOf, expr(exp), create unresolved_name indepOf)
    case ValPrimary5("(", x, "\\memberof", xs, ")") =>
      create expression(Member, expr(x), expr(xs))
    case ValPrimary6("[", from, "..", to, ")") =>
      create expression(RangeSeq, expr(from), expr(to))
    case ValPrimary7("*") =>
      create reserved_name ASTReserved.Any
    case ValPrimary8("\\current_thread") =>
      create reserved_name ASTReserved.CurrentThread
    case ValPrimary9(_, binderName, t, id, "=", fr, "..", to, _, main, _) =>
      val name = convertID(id)
      val decl = create field_decl(name, convertType(t))
      val guard = create expression(And,
        create expression(LTE, expr(fr), create unresolved_name(name)),
        create expression(StandardOperator.LT, create unresolved_name(name), expr(to))
      )
      binderName match {
        case "\\forall*" => create starall(guard, expr(main), decl)
        case "\\forall" => create forall(guard, expr(main), decl)
        case "\\exists" => create exists(guard, expr(main), decl)
      }
    case ValPrimary10(_, binderName, t, id, _, guard, _, main, _) =>
      val decl = create field_decl(convertID(id), convertType(t))
      binderName match {
        case "\\forall*" => create starall(expr(guard), expr(main), decl)
        case "\\forall" => create forall(expr(guard), expr(main), decl)
        case "\\exists" => create exists(expr(guard), expr(main), decl)
      }
    case ValPrimary11(_, "\\let", t, id, "=", exp, _, body, _) =>
      create let_expr(create field_decl(convertID(id), convertType(t), expr(exp)), expr(body))
    case ValPrimary12(_, "\\sum", t, id, _, guard, _, main, _) =>
      create summation(expr(guard), expr(main), create field_decl(convertID(id), convertType(t)))
    case ValPrimary13("\\length", "(", exp, ")") =>
      create expression(Length, expr(exp))
    case ValPrimary14("\\old", "(", exp, ")") =>
      create expression(Old, expr(exp))
    case ValPrimary15("\\id", "(", exp, ")") =>
      create expression(Identity, expr(exp))
    case ValPrimary16("\\typeof", "(", exp, ")") =>
      create expression(TypeOf, expr(exp))
    case ValPrimary17("\\matrix", "(", m, _, size0, _, size1, ")") =>
      create expression(ValidMatrix, expr(m), expr(size0), expr(size1))
    case ValPrimary18("\\array", "(", a, _, size0, ")") =>
      create expression(ValidArray, expr(a), expr(size0))
    case ValPrimary19("\\pointer", "(", p, _, size0, _, perm, ")") =>
      create expression(ValidPointer, expr(p), expr(size0), expr(perm))
    case ValPrimary20("\\pointer_index", "(", p, _, idx, _, perm, ")") =>
      create expression(ValidPointerIndex, expr(p), expr(idx), expr(perm))
    case ValPrimary21("\\values", "(", a, _, fr, _, to, ")") =>
      create expression(Values, expr(a), expr(fr), expr(to))
    case ValPrimary22("\\sum", "(", a, _, b, ")") =>
      create expression(FoldPlus, expr(a), expr(b))
    case ValPrimary23("\\vcmp", "(", a, _, b, ")") =>
      create expression(VectorCompare, expr(a), expr(b))
    case ValPrimary24("\\vrep", "(", v, ")") =>
      create expression(VectorRepeat, expr(v))
    case ValPrimary25("\\msum", "(", a, _, b, ")") =>
      create expression(MatrixSum, expr(a), expr(b))
    case ValPrimary26("\\mcmp", "(", a, _, b, ")") =>
      create expression(MatrixCompare, expr(a), expr(b))
    case ValPrimary27("\\mrep", "(", m, ")") =>
      create expression(MatrixRepeat, expr(m))
    case ValPrimary28("Reducible", "(", exp, _, "+", ")") =>
      create expression(ReducibleSum, expr(exp))
    case ValPrimary28("Reducible", "(", exp, _, "min", ")") =>
      create expression(ReducibleMin, expr(exp))
    case ValPrimary28("Reducible", "(", exp, _, "max", ")") =>
      create expression(ReducibleMax, expr(exp))
  })

  def convertValReserved(reserved: ValReservedContext): NameExpression = origin(reserved, reserved match {
    case ValReserved0(_) =>
      fail(reserved, "This identifier is reserved and cannot be declared or used.")
    case ValReserved1("\\result") =>
      create reserved_name ASTReserved.Result
    case ValReserved2("\\current_thread") =>
      create reserved_name ASTReserved.CurrentThread
    case ValReserved3("none") =>
      create reserved_name ASTReserved.NoPerm
    case ValReserved4("write") =>
      create reserved_name ASTReserved.FullPerm
    case ValReserved5("read") =>
      create reserved_name ASTReserved.ReadPerm
    case ValReserved6("None") =>
      create reserved_name ASTReserved.OptionNone
    case ValReserved7("empty") =>
      create reserved_name ASTReserved.EmptyProcess
  })

  def convertValContract(contract: ValEmbedContractContext, builder: ContractBuilder): Unit = contract match {
    case ValEmbedContract0(blocks) =>
      for(block <- blocks) {
        convertValContractBlock(block, builder)
      }
  }

  def convertValContractBlock(contract: ValEmbedContractBlockContext, builder: ContractBuilder): Unit = contract match {
    case ValEmbedContractBlock0(_startSpec, clauses, _endSpec) =>
      for(clause <- clauses) {
        convertValClause(clause, builder)
      }
  }

  /* === End of duplicated code block === */
}
