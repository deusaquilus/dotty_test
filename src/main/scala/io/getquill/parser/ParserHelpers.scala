package io.getquill.parser

import io.getquill.ast.{Ident => AIdent, Query => AQuery, _}
import io.getquill._
import scala.quoted._
import scala.quoted.{Const => ConstExpr}
import scala.annotation.StaticAnnotation
import scala.deriving._
import io.getquill.Embedable
import io.getquill.Dsl
import scala.reflect.ClassTag
import io.getquill.norm.capture.AvoidAliasConflict
import io.getquill.metaprog.QuotationLotExpr
import io.getquill.EntityQuery
import io.getquill.Query
import io.getquill.util.Format
import io.getquill.parser.ParserHelpers._
import io.getquill.quat.QuatMaking
import io.getquill.quat.Quat
import io.getquill.metaprog.Extractors

object ParserHelpers {

  trait Idents(implicit override val qctx: Quotes) extends Extractors with QuatMaking {
    import quotes.reflect.{Ident => TIdent, ValDef => TValDef, _}

    def cleanIdent(name: String, quat: Quat): AIdent = AIdent(name.replace("_$", "x"), quat)
    def cleanIdent(name: String, tpe: TypeRepr): AIdent = AIdent(name.replace("_$", "x"), InferQuat.ofType(tpe))
  }
  
  trait Assignments(implicit override val qctx: Quotes) extends Idents with Extractors {
    import quotes.reflect.{Ident => TIdent, ValDef => TValDef, _}
    import Parser.Implicits._
    import io.getquill.util.Interpolator
    import io.getquill.util.Messages.TraceType
    import io.getquill.norm.BetaReduction

    def astParse: SealedParser[Ast]

    object AssignmentTerm {
      def OrFail(term: Term) =
        unapply(term).getOrElse { Parser.throwExpressionError(term.asExpr, classOf[Assignment]) }

      def unapply(term: Term): Option[Assignment] =
        UntypeExpr(term.asExpr) match {
          case Lambda1(ident, identTpe, '{ type v; ($prop: Any).->[`v`](($value: `v`)) }) => 
            Some(Assignment(cleanIdent(ident, identTpe), astParse(prop), astParse(value)))
          case _ => None
        }
    }
  }


  trait PropertyAliases(implicit val qctx: Quotes) extends Extractors {
    import quotes.reflect.{Ident => TIdent, ValDef => TValDef, _}
    import Parser.Implicits._
    import io.getquill.util.Interpolator
    import io.getquill.util.Messages.TraceType
    import io.getquill.norm.BetaReduction

    def astParse: SealedParser[Ast]

    object PropertyAliasExpr {
      def OrFail[T: Type](expr: Expr[Any]) = expr match
          case PropertyAliasExpr(propAlias) => propAlias
          case _ => Parser.throwExpressionError(expr, classOf[PropertyAlias])

      def unapply[T: Type](expr: Expr[Any]): Option[PropertyAlias] = expr match
        case Lambda1(_, _, '{ ($prop: Any).->[v](${ConstExpr(alias: String)}) } ) =>
          def path(tree: Expr[_]): List[String] =
            tree match
              case a`.`b => 
                path(a) :+ b
              case '{ (${a`.`b}: Option[t]).map[r](${Lambda1(arg, tpe, body)}) } =>
                path(a) ++ (b :: path(body))
              case _ => 
                Nil
          end path
          Some(PropertyAlias(path(prop), alias))
        case _ => 
          None
    }    
      
  }

  trait PatternMatchingValues(implicit override val qctx: Quotes) extends Extractors with QuatMaking {
    import quotes.reflect.{Ident => TIdent, ValDef => TValDef, _}
    import Parser.Implicits._
    import io.getquill.util.Interpolator
    import io.getquill.util.Messages.TraceType
    import io.getquill.norm.BetaReduction

    def astParse: SealedParser[Ast]

    // don't change to ValDef or might override the real valdef in qctx.reflect
    object ValDefTerm {
      def unapply(tree: Tree): Option[Ast] =
        tree match {
          case TValDef(name, tpe, Some(t @ PatMatchTerm(ast))) =>
            println(s"====== Parsing Val Def ${name} = ${t.show}")
            Some(Val(AIdent(name, InferQuat.ofType(tpe.tpe)), ast))

          // In case a user does a 'def' instead of a 'val' with no paras and no types then treat it as a val def
          // this is useful for things like (TODO Get name) where you'll have something like:
          // query[Person].map(p => (p.name, p.age)).filter(tup => tup._1.name == "Joe")
          // But in Scala3 you can do:
          // query[Person].map(p => (p.name, p.age)).filter((name, age) => name == "Joe")
          // Then in the AST it will look something like:
          // query[Person].map(p => (p.name, p.age)).filter(x$1 => { val name=x$1._1; val age=x$1._2; name == "Joe" })
          // and you need to resolve the val defs thare are created automatically
          case DefDef(name, _, paramss, tpe, rhsOpt) if (paramss.length == 0) =>
            //println(s"====== Parsing Def Def ${name} = ${rhsOpt.map(_.show)}")
            val body =
              rhsOpt match {
                // TODO Better site-description in error
                case None => report.throwError(s"Cannot parse 'val' clause with no '= rhs' (i.e. equals and right hand side) of ${Printer.TreeStructure.show(tree)}")
                case Some(rhs) => rhs
              }
            val bodyAst = astParse(body.asExpr)
            Some(Val(AIdent(name, InferQuat.ofType(tpe.tpe)), bodyAst))

          case TValDef(name, tpe, rhsOpt) =>
            val body =
              rhsOpt match {
                // TODO Better site-description in error
                case None => report.throwError(s"Cannot parse 'val' clause with no '= rhs' (i.e. equals and right hand side) of ${Printer.TreeStructure.show(tree)}")
                case Some(rhs) => rhs
              }
            val bodyAst = astParse(body.asExpr)
            Some(Val(AIdent(name, InferQuat.ofType(tpe.tpe)), bodyAst))

          case _ => None
        }
    }

    object PatMatchTerm {
      def unapply(term: Term): Option[Ast] =
        term match {
          case Match(expr, List(CaseDef(fields, guard, body))) =>
            guard match {
              case Some(guardTerm) => report.throwError("Guards in case- match are not supported", guardTerm.asExpr)
              case None =>
            }
            Some(patMatchParser(expr, fields, body))

          case other => None
        }
    }
    
    protected def patMatchParser(tupleTree: Term, fieldsTree: Tree, bodyTree: Term): Ast = {
      val tuple = astParse(tupleTree.asExpr)
      val body = astParse(bodyTree.asExpr)

      /* 
      Get a list of all the paths of all the identifiers inside the tuple. E.g:
      foo match { case ((a,b),c) => bar } would yield something like:
      List((a,List(_1, _1)), (b,List(_1, _2)), (c,List(_2)))
      */
      def tupleBindsPath(field: Tree, path: List[String] = List()): List[(AIdent, List[String])] = {
        UntypeTree(field) match {
          case Bind(name, TIdent(_)) => List(AIdent(name) -> path)
          case Unapply(Method0(TupleIdent(), "unapply"), something, binds) => 
            binds.zipWithIndex.flatMap { case (bind, idx) =>
              tupleBindsPath(bind, path :+ s"_${idx + 1}")
            }
          case other => report.throwError(s"Invalid Pattern Matching Term: ${Printer.TreeStructure.show(other)}")
        }
      }

      /* Take the list found in the tupleBindsPath method above and match up each match-tuple element 
      from the original tuple we found. For example, if we had: foo match { case ((a,b),c) => bar }
      we get something like List((a,List(_1, _1)), (b,List(_1, _2)), (c,List(_2))). If 'foo'
      is ((f,b),z) then we want to get: List(((f,b),z)._1._1, ((f,b),z)._1._2, ((f,b),z)._2)
      */
      def propertyAt(path: List[String]) =
        path.foldLeft(tuple) {
          case (tup, elem) => Property(tup, elem)
        }

      val fieldPaths = tupleBindsPath(fieldsTree)
      val reductionTuples = fieldPaths.map((id, path) => (id, propertyAt(path)))

      val interp = new Interpolator(TraceType.Standard, 1)
      import interp._

      trace"Pat Match Parsing: ${body}".andLog()
      trace"Reductions: ${reductionTuples}".andLog()
      // Do not care about types here because pat-match body does not necessarily have correct typing in the Parsing phase
      BetaReduction(body, reductionTuples: _*)
    }
  }

}