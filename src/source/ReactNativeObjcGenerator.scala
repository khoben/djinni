/**
  * Copyright 2014 Dropbox, Inc.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *    http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package djinni

import djinni.ast.Record.DerivingType
import djinni.ast._
import djinni.generatorTools._
import djinni.meta._
import djinni.syntax.Error
import djinni.writer.IndentWriter

import scala.collection.mutable
import scala.collection.parallel.immutable

class ReactNativeObjcGenerator(spec: Spec, objcInterfaces : Seq[String]) extends ObjcGenerator(spec) {

  class ReactNativeRefs() {
    var body = mutable.TreeSet[String]()
    var header = mutable.TreeSet[String]()

    def find(ty: TypeRef, importRCT: Boolean = false) { find(ty.resolved, importRCT) }
    def find(tm: MExpr, importRCT: Boolean) {
      tm.args.foreach(t => find(t, importRCT))
      find(tm.base, importRCT)
    }
    def find(m: Meta, importRCT: Boolean) = for(r <- marshal.reactReferences(m)) r match {
      case ImportRef(arg) => {
        header.add("#import " + arg)
        if (importRCT && !isEnum(MExpr(m, List())) && arg.indexOf("\"") == 0) {
          val rctHeader = s""""${spec.reactNativeTypePrefix}${arg.substring(1)}"""
          header.add("#import " + rctHeader)
        }
      }
      case DeclRef(decl, _) => header.add(decl)
    }
  }

  override def generateEnum(origin: String, ident: Ident, doc: Doc, e: Enum) {

  }


  def reactInterfaceType(tm: MExpr) : String = tm.base match {
    case MOptional => s"nullable ${reactInterfaceType(tm.args.head)}"
    case MList => s"NSArray <${reactInterfaceType(tm.args.head)}> *"
    case MSet => s"NSSet <${reactInterfaceType(tm.args.head)}> *"
    case MMap => s"NSDictionary <${reactInterfaceType(tm.args.head)}> *"
    case d: MDef =>
      d.defType match {
        case DInterface | DRecord => "NSDictionary *"
        case _ => ""
      }
    case _ => ""
  }

  def isExprInterface(tm: MExpr): Boolean = tm.base match {
    case MOptional | MList | MSet | MMap => isExprInterface(tm.args.head)
    case d: MDef =>
      d.defType match {
        case DInterface => true
        case _ => false
      }
    case _ => false
  }

  def isExprRecord(tm: MExpr): Boolean = tm.base match {
    case MOptional | MList | MSet | MMap => isExprRecord(tm.args.head)
    case d: MDef =>
      d.defType match {
        case DRecord => true
        case _ => false
      }
    case _ => false
  }

  def isBinary(tm: MExpr): Boolean = tm.base match {
    case MBinary => true
    case _ => false
  }

  def generateParams(p: Field): Option[(String, String)] = {
    val localIdent = idObjc.local(p.ident)
    val identity = idObjc.field(p.ident)

    if (isExprInterface(p.ty.resolved) || isExprRecord(p.ty.resolved)) {
      Some(identity, s"(${reactInterfaceType(p.ty.resolved)})$localIdent")
    } else {
      val paramType = marshal.paramType(p.ty)
      val findIntType = """int\d+_t""".r
      findIntType.findFirstIn(paramType) match {
        case Some(_) => Some(identity, s"(int)$localIdent")
        case None => Some(identity, s"($paramType)$localIdent")
      }
    }
  }
  def generateInitMethod(wr : IndentWriter, hasOneFieldAsInterface: Boolean = false): Unit = {
    wr.wl("-(instancetype)init").braced {
      wr.wl("self = [super init];")
      wr.wl("//Init Objc implementation")
      wr.wl("if(self)").braced {
        wr.wl(s"self.objcImplementations = [[NSMutableDictionary alloc] init];")
        if (hasOneFieldAsInterface) {
          wr.wl(s"self.implementationsData = [[NSMutableDictionary alloc] init];")
        }
      }
      wr.wl("return self;")
    }
  }

  def generateInitMethodForCallback(wr : IndentWriter, isDeclaration: Boolean = false): Unit = {
    val decl = "-(instancetype)initWithResolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock) reject andBridge: (RCTBridge *) bridge"
    if (isDeclaration) {
      wr.wl(s"$decl;")
    } else {
      wr.wl(decl).braced {
        wr.wl("self = [super init];")
        wr.wl("if(self)").braced {
          wr.wl(s"self.resolve = resolve;")
          wr.wl(s"self.reject = reject;")
          wr.wl(s"self.bridge = bridge;")
        }
        wr.wl("return self;")
      }
    }
  }

  def generateReleaseMethod(wr : IndentWriter, objcInterface: String): Unit = {
    wr.wl("RCT_REMAP_METHOD(release, release:(NSDictionary *)currentInstance withResolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)").braced {
      val rctItf = spec.reactNativeTypePrefix + objcInterface
      wr.wl("""if (!currentInstance[@"uid"] || !currentInstance[@"type"])""").braced {
        wr.wl(s"""reject(@"impl_call_error", @"Error while calling $rctItf::release, first argument should be an instance of $objcInterface", nil);""")
      }
      wr.wl("""[self.objcImplementations removeObjectForKey:currentInstance[@"uid"]];""")
      wr.wl("resolve(@(YES));")
    }
  }

  def generateLogInstancesMethod(wr : IndentWriter): Unit = {
    wr.wl("RCT_REMAP_METHOD(log, logWithResolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)").braced {
      wr.wl("NSMutableArray *uuids = [[NSMutableArray alloc] init];")
      wr.wl(s"for (id key in self.objcImplementations)").braced {
        wr.wl("[uuids addObject:key];")
      }
      wr.wl("""NSDictionary *result = @{@"value" : uuids};""")
      wr.wl("resolve(result);")
    }
  }

  def generateFlushInstancesMethod(wr : IndentWriter): Unit = {
    wr.wl("RCT_REMAP_METHOD(flush, flushWithResolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)").braced {
      wr.wl(s"[self.objcImplementations removeAllObjects];")
      wr.wl("resolve(@(YES));")
    }
  }

  /*
  As always, we suppose that callbacks implement only one method: onCallback,
  it has 2 arguments, in order, first one is result and second one error
  */
  def isCallbackInterface(ident: Ident, i: Interface): Boolean = {
    if (marshal.typename(ident, i).contains("Callback")) {
      i.methods.length == 1 && i.methods.filter(m => m.ident.name == "onCallback").length > 0 && i.methods.head.params.length == 2
    } else {
      false
    }
  }

  //Allows to construct name of RCT classes (cpp or objc implemented interfaces)
  def getRCTName(paramTypeName: String) : String  = {
    if (paramTypeName.indexOf("id<") >= 0 && paramTypeName.indexOf(">") == paramTypeName.length - 1) {
      paramTypeName.slice(paramTypeName.indexOf("<") + 1, paramTypeName.indexOf(">"))
    } else {
      paramTypeName
    }
  }
  
  def toReactType(tm: MExpr, converted: String, converting: String, wr: IndentWriter): Unit = tm.base match {
    case MOptional => toReactType(tm.args.head, converted, converting, wr)
    case MList => {
      wr.wl(s"NSMutableArray *$converted = [[NSMutableArray alloc] init];")
      wr.wl(s"for (id ${converting}_elem in $converting)").braced {
        toReactType(tm.args.head, s"${converted}_elem", s"${converting}_elem", wr)
        wr.wl(s"[$converted addObject:${converted}_elem];")
      }
    }
    case MSet => {
      wr.wl(s"NSMutableSet *$converted = [[NSMutableSet alloc] init];")
      wr.wl(s"NSArray *arrayFromSet_$converting = [$converting allObjects];")
      wr.wl(s"for (id ${converting}_elem in arrayFromSet_$converting)").braced {
        toReactType(tm.args.head, s"${converted}_elem", s"${converting}_elem", wr)
        wr.wl(s"[$converted addObject:${converted}_elem];")
      }
    }
    case MMap => {
      wr.wl(s"NSMutableDictionary *$converted = [[NSMutableDictionary alloc] init];")
      wr.wl(s"for (id ${converting}_key in $converting)").braced {
        wr.wl(s"id ${converted}_value = [$converting objectForKey:${converted}_key];")
        toReactType(tm.args.head, s"${converted}_value", s"${converting}_value", wr)
        wr.wl(s"[$converted setObject:${converted}_value forKey:${converted}_key];")
      }
    }
    case d: MDef =>
      d.defType match {
        case DInterface | DRecord => {
          wr.wl("NSString *uuid = [[NSUUID UUID] UUIDString];")
          //Bridge is shortning prefix if it's starting with RCT
          val prefix = "RCT"
          val objcParamType = getRCTName(marshal.typename(tm))
          val paramTypeName = spec.reactNativeTypePrefix + objcParamType
          val moduleName = if (paramTypeName.indexOf(prefix) == 0) paramTypeName.substring(prefix.length) else paramTypeName
          wr.wl(s"""$paramTypeName *rctImpl_$converting = ($paramTypeName *)[self.bridge moduleForName:@"$moduleName"];""")
          wr.wl(s"[rctImpl_$converting.objcImplementations setObject:$converting forKey:uuid];")
          wr.wl(s"""NSDictionary *$converted = @{@"type" : @"$moduleName", @"uid" : uuid };""")
        }
        case _ =>
      }
    case _ =>
  }

  def fromReactType(tm: MExpr, ident: Ident, converted: String, converting: String, wr: IndentWriter, dataContainer: String = "", hasParentContainer: Boolean = false, hasReturnValue: Boolean = true): Unit = tm.base match {
    case MOptional => fromReactType(tm.args.head, ident, converted, converting, wr, dataContainer, hasParentContainer)
    case MList => {
      wr.wl(s"NSMutableArray *$converted = [[NSMutableArray alloc] init];")
      wr.wl
      if (dataContainer.length > 0) {
        wr.wl(s"NSMutableArray *${converted}_data = [[NSMutableArray alloc] init];")
        wr.wl
      }
      wr.wl(s"for (id ${converting}_elem in $converting)").braced {
        fromReactType(tm.args.head, ident, s"${converted}_elem", s"${converting}_elem", wr, s"${converted}_data", true)
        wr.wl(s"[$converted addObject:${converted}_elem];")
        wr.wl
      }
      if (dataContainer.length > 0) {
        wr.wl(s"""[$dataContainer setObject:${converted}_data forKey:@"${idJava.field(ident)}"];""")
        wr.wl
      }
    }
    case MSet => {
      wr.wl(s"NSMutableSet *$converted = [[NSMutableSet alloc] init];")
      wr.wl(s"NSArray *arrayFromSet_$converting = [$converting allObjects];")
      wr.wl
      if (dataContainer.length > 0) {
        wr.wl(s"NSMutableArray *${converted}_data = [[NSMutableArray alloc] init];")
        wr.wl
      }
      wr.wl(s"for (id ${converting}_elem in arrayFromSet_$converting)").braced {
        fromReactType(tm.args.head, ident, s"${converted}_elem", s"${converting}_elem", wr, s"${converted}_data", true)
        wr.wl(s"[$converted addObject:${converted}_elem];")
      }
      if (dataContainer.length > 0) {
        wr.wl(s"""[$dataContainer setObject:${converted}_data forKey:@"${idJava.field(ident)}"];""")
        wr.wl
      }
    }
    case MMap => {
      wr.wl(s"NSMutableDictionary *$converted = [[NSMutableDictionary alloc] init];")
      wr.wl
      if (dataContainer.length > 0) {
        wr.wl(s"NSMutableArray *${converted}_data = [[NSMutableArray alloc] init];")
        wr.wl
      }
      wr.wl(s"for (id ${converting}_key in $converting)").braced {
        wr.wl(s"id ${converted}_value = [$converting objectForKey:${converted}_key];")
        fromReactType(tm.args.head, ident, s"${converted}_value", s"${converting}_value", wr, s"${converted}_data", true)
        wr.wl(s"[$converted setObject:${converted}_value forKey:${converted}_key];")
      }
      if (dataContainer.length > 0) {
        wr.wl(s"""[$dataContainer setObject:${converted}_data forKey:@"${idJava.field(ident)}"];""")
        wr.wl
      }
    }
    case d: MDef =>
      d.defType match {
        case DInterface | DRecord => {
          //Bridge is shortning prefix if it's starting with RCT
          val prefix = "RCT"
          val paramTypeName = marshal.typename(tm)
          val objcParamType = getRCTName(paramTypeName)
          val rctParamType = spec.reactNativeTypePrefix + objcParamType
          val isObjcImplemented = objcInterfaces.contains(objcParamType.substring(idObjc.ty("").length))
          val finalObjcParamType = s"$paramTypeName${if (isObjcImplemented) "" else " *"}"
          val moduleName = if (rctParamType.indexOf(prefix) == 0) rctParamType.substring(prefix.length) else rctParamType
          wr.wl(s"""$rctParamType *rctParam_${converting} = ($rctParamType *)[self.bridge moduleForName:@"$moduleName"];""")
          wr.wl(s"""${finalObjcParamType}$converted = ($finalObjcParamType)[rctParam_${converting}.objcImplementations objectForKey:$converting[@"uid"]];""")

          //If resolver and rejecter not used by method (method without return type),
          //we set resolver and rejecter on platform specific interfaces so we can use them
          if (!hasReturnValue && isObjcImplemented) {
            //Needs conversion to impl type
            wr.wl(s"$objcParamType${spec.reactNativeObjcImplSuffix} *${converted}_objc = ($objcParamType${spec.reactNativeObjcImplSuffix} *)$converted;")
            wr.wl(s"if (${converted}_objc)").braced {
              wr.wl(s"${converted}_objc.resolve = resolve;")
              wr.wl(s"${converted}_objc.reject = reject;")
            }
          }

          if (dataContainer.length > 0 && hasParentContainer) {
            wr.wl(s"""[$dataContainer addObject:$converting[@"uid"]];""")
          } else if (dataContainer.length > 0) {
            wr.wl(s"""[$dataContainer setObject:$converting[@"uid"] forKey:@"${idJava.field(ident)}"];""")
          }
        }
        case _ =>
      }
    case _ =>
  }
  /**
    * Generate Interface
    **/
  override def generateInterface(origin: String, ident: Ident, doc: Doc, typeParams: Seq[TypeParam], i: Interface) {
    val refs = new ReactNativeRefs()
    i.methods.map(m => {
      val addRCTHeader = true
      m.params.map(p => refs.find(p.ty, addRCTHeader))
      m.ret.foreach(r => refs.find(r, addRCTHeader))
    })
    i.consts.map(c => {
      refs.find(c.ty)
    })

    val callbackInterface = isCallbackInterface(ident, i)
    val objcInterface = if(i.ext.objc) marshal.typename(ident, i) + spec.reactNativeObjcImplSuffix else marshal.typename(ident, i)
    val self = spec.reactNativeTypePrefix + marshal.typename(ident, i)
    refs.header.add("#import <Foundation/Foundation.h>")
    refs.header.add("#import <React/RCTBridgeModule.h>")
    refs.header.add("#import <React/RCTBridge.h>")
    //Include
    val pathToObjcImpl = s""""${objcInterface}.h""""
    refs.header.add(s"#import $pathToObjcImpl")

    def writeObjcFuncDecl(method: Interface.Method, w: IndentWriter) {
      val methodIdent = idObjc.method(method.ident)
      //Special treatment for callbacks since we're using promises
      if(callbackInterface) {
        //We don't need to expose callbacks to React Native
        val label = if (method.static) "+" else "-"
        val ret = marshal.returnType(method.ret)
        val decl = s"$label ($ret)$methodIdent"
        writeAlignedObjcCall(w, decl, method.params, "", p => (idObjc.field(p.ident), s"(${marshal.paramType(p.ty)})${idObjc.local(p.ident)}"))
      } else {
        val ret = marshal.returnType(method.ret)
        val hasOnlyCallback = method.params.length == 1 && (marshal.paramType(method.params(0).ty).contains("callback") || marshal.paramType(method.params(0).ty).contains("Callback"))
        val hasNoParams = method.params.length == 0 || hasOnlyCallback
        val firstParam = s"""${if (!method.static) ":(NSDictionary *)currentInstance " else ""}"""

        val declEnd = s"$firstParam${if (hasNoParams) "" else "withParams"}"
        val decl = s"RCT_REMAP_METHOD($methodIdent,$methodIdent${declEnd}"
        writeAlignedReactNativeCall(w, decl, method.params, "", p => {
          //No callbacks
          if (!marshal.paramType(p.ty).contains("Callback")) {
            generateParams(p)
          } else {
            None
          }
        })
        val begin = if(hasNoParams) "WithResolver" else " withResolver"
        w.w(s"${begin}:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject")
      }
    }

    // Generate the header file for Interface
    val fileName = spec.reactNativeTypePrefix + marshal.headerName(ident)
    writeObjcFile(fileName, origin, refs.header, w => {
      w.wl
      writeDoc(w, doc)
      if(callbackInterface) {
        w.wl(s"@interface $self : NSObject <${marshal.typename(ident, i)}>")
        w.wl(s"@property (nonatomic, strong) RCTPromiseResolveBlock resolve;")
        w.wl(s"@property (nonatomic, strong) RCTPromiseRejectBlock reject;")
        w.wl(s"@property (nonatomic, weak) RCTBridge *bridge;")
        val isDeclaration = true
        generateInitMethodForCallback(w, isDeclaration)
        w.wl("@end")
      } else {
        w.wl(s"@interface $self : NSObject <RCTBridgeModule>")
        w.wl(s"@property (nonatomic, strong) NSMutableDictionary *objcImplementations;")
        w.wl("@end")
      }
    })

    // Generate the implementation file for Interface
    refs.body.add("#import " + q(spec.reactNativeTypePrefix + marshal.headerName(ident)))
    val implfileName = spec.reactNativeTypePrefix + idObjc.ty(ident.name) + ".m"
    writeObjcFile(implfileName, origin, refs.body, w => {
      w.wl
      w.wl(s"@implementation $self")
      if(callbackInterface) {
        generateInitMethodForCallback(w)
      } else {
        w.wl("//Export module")
        w.wl(s"RCT_EXPORT_MODULE($self)")
        w.wl
        w.wl(s"@synthesize bridge = _bridge;")
        w.wl
        generateInitMethod(w)
        //Avoid all warnings due to this method
        w.wl
        w.wl("+ (BOOL)requiresMainQueueSetup").braced {
          w.wl("return NO;")
        }
        //Release to remove objc instance from self.objcImplementations
        generateReleaseMethod(w, marshal.typename(ident, i))
        //Returns uid of all objc instances
        generateLogInstancesMethod(w)
        //Flush all objc intances from React Native Module's objcImplementations attribute
        generateFlushInstancesMethod(w)
      }

      var hasFactoryMethod = false
      for (m <- i.methods) {
        hasFactoryMethod = hasFactoryMethod || (m.static && m.ret.isDefined && marshal.paramType(m.ret.get).equals(marshal.typename(ident, i)))
        w.wl
        writeMethodDoc(w, m, idObjc.local)
        writeObjcFuncDecl(m, w)
        w.w(s"${if (callbackInterface) "" else ")" }").braced {

          //Construct call
          val ret = marshal.returnType(m.ret)
          val boxResult = if (m.ret.isDefined) marshal.toBox(m.ret.get.resolved) else false

          if(callbackInterface) {
            //Case param is itf or container of itfs
            val errorParam = m.params(1)
            val resultParam = m.params(0)
            val isParamInterface = isExprInterface(resultParam.ty.resolved)
            val isParamRecord = isExprRecord(resultParam.ty.resolved)
            //Case of error
            w.wl(s"if (${idObjc.field(errorParam.ident)})").braced {
              //Suppose that error has always a 'message' attribute
              w.wl(s"""self.reject(@"$self Error", ${idObjc.field(errorParam.ident)}.message, nil);""")
              w.wl("return;")
            }
            w.wl
            //TODO: Handle Enums
            if (isParamInterface || isParamRecord) {
              //Callback has result and callback args
              toReactType(resultParam.ty.resolved, "converted_result", idObjc.field(resultParam.ident), w)
            }
            w.wl
            w.wl(s"self.resolve(${if (isParamInterface || isParamRecord) "converted_result" else idObjc.field(resultParam.ident)});")
          } else {

            if (!m.static) {
              //Get current Instance
              w.wl("""if (!currentInstance[@"uid"] || !currentInstance[@"type"])""").braced {
                w.wl(s"""reject(@"impl_call_error", @"Error while calling $self::${idObjc.method(m.ident)}, first argument should be an instance of ${objcInterface}", nil);""")
              }

              w.wl(s"""${objcInterface} *currentInstanceObj = [self.objcImplementations objectForKey:currentInstance[@"uid"]];""")
              w.wl("if (!currentInstanceObj)").braced {
                w.wl(s"""NSString *error = [NSString stringWithFormat:@"Error while calling ${objcInterface}::${idObjc.method(m.ident)}, instance of uid %@ not found", currentInstance[@"uid"]];""")
                w.wl(s"""reject(@"impl_call_error", error, nil);""")
              }
            }

            //Retrieve from bridge if necessary
            m.params.foreach(p =>{
              if (isExprInterface(p.ty.resolved) || isExprRecord(p.ty.resolved)) {
                val index = m.params.indexOf(p)
                //Bridge is shortning prefix if it's starting with RCT
                val prefix = "RCT"
                val paramTypeName = marshal.typename(p.ty)
                val objcParamType = getRCTName(paramTypeName)
                val rctParamType = spec.reactNativeTypePrefix + objcParamType
                val isObjcImplemented = objcInterfaces.contains(objcParamType.substring(idObjc.ty("").length))
                val finalObjcParamType = s"$paramTypeName${if (isObjcImplemented) "" else " *"}"
                if (paramTypeName.contains("Callback")) {
                  //Construct RCT callback from resolver and rejecter
                  w.wl(s"$rctParamType *objcParam_${index} = [[$rctParamType alloc] initWithResolver:resolve rejecter:reject andBridge:self.bridge];")
                } else {
                  //TODO: check if parameters are having "type" and "uid" fields
                  val dataContainer = ""
                  val hasParentContainer = false
                  fromReactType(p.ty.resolved, p.ident, s"objcParam_$index", idObjc.field(p.ident), w, dataContainer, hasParentContainer, ret != "void")
                }
              }
            })

            //Start calling Objective-C method
            if (m.static || ret != "void") {
              w.w(s"${marshal.fieldType(m.ret.get)} objcResult = ")
            }
            w.w(s"[${if (m.static) objcInterface else "currentInstanceObj"} ${idObjc.method(m.ident)}")
            //Parameter call
            m.params.foreach(p =>{
              val index = m.params.indexOf(p)
              val start = if (p == m.params(0)) "" else s" ${idObjc.field(p.ident)}"
              val param = if (isExprInterface(p.ty.resolved) || isExprRecord(p.ty.resolved))  s"objcParam_${index}" else idObjc.field(p.ident)
              w.w(s"${start}:${param}")
            })

            w.wl("];")
            if(ret != "void") {
              //Add to implementations
              if (m.ret.isDefined && (isExprInterface(m.ret.get.resolved) || isExprRecord(m.ret.get.resolved))) {
                //Check if it's a platform specific implementation (i.e. extCpp = true)
                //This check should rely on a more robust test, go through idls and find corresponding interface and test ?
                val paramTypeName = marshal.typename(m.ret.get)
                val objcParamType = getRCTName(paramTypeName)
                val rctReturn = spec.reactNativeTypePrefix + objcParamType
                //Bridge is shortning prefix if it's starting with RCT
                val prefix = "RCT"
                val moduleName = if (rctReturn.indexOf(prefix) == 0) rctReturn.substring(prefix.length) else rctReturn
                w.wl
                toReactType(m.ret.get.resolved, "result", "objcResult", w)
              } else {

                //If result is NSDate use NSDateFormatter
                m.ret.get.resolved.base match {
                  case MDate => {
                    w.wl("NSDateFormatter *dateFormatter = [[NSDateFormatter alloc] init];")
                    w.wl("[dateFormatter setDateFormat:@\"YYYY-MM-dd HH:mm:ss\"];")
                    w.wl("NSString *objcResultDate = [dateFormatter stringFromDate:objcResult];")
                  }
                  case _ =>
                }

                w.w("""NSDictionary *result = @{@"value" : """)
                if (boxResult) {
                  w.w("@(")
                }

                val objcResult = m.ret.get.resolved.base match {
                  case MBinary => "objcResult.description"
                  case MDate => "objcResultDate"
                  case _ => "objcResult"
                }
                w.w(s"$objcResult")

                if (boxResult) {
                  w.w(")")
                }
                w.w("};")
              }

              w.wl
              w.wl("if(result)").braced {
                w.wl("resolve(result);")
              }
              w.wl("else").braced {
                //Send a null NSError object for the moment
                w.wl(s"""reject(@"impl_call_error", @"Error while calling ${objcInterface}::${idObjc.method(m.ident)}", nil);""")
              }
            }
          }
          w.wl
        }
      }

      //If no factory method, create a default one
      if (!callbackInterface && !hasFactoryMethod && i.ext.objc) {
        w.w(s"RCT_REMAP_METHOD(newInstance, newInstanceWithResolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)").braced {
          w.wl(s"$objcInterface *objcResult = [[$objcInterface alloc] init];")
          w.wl("NSString *uuid = [[NSUUID UUID] UUIDString];")
          w.wl("[self.objcImplementations setObject:objcResult forKey:uuid];")
          val prefix = "RCT"
          val rctType = spec.reactNativeTypePrefix + objcInterface
          val moduleName = if (rctType.indexOf(prefix) == 0) rctType.substring(prefix.length) else rctType
          w.wl(s"""NSDictionary *result = @{@"type" : @"$moduleName", @"uid" : uuid };""")
          w.wl("if (!objcResult || !result)").braced {
            w.wl(s"""reject(@"impl_call_error", @"Error while calling ${rctType}::init", nil);""")
          }
          w.wl("resolve(result);")
        }
      }
      w.wl("@end")
    })

  }

  override def generateRecord(origin: String, ident: Ident, doc: Doc, params: Seq[TypeParam], r: Record) {
    val refs = new ReactNativeRefs()
    val addRCTHeader = true
    for (c <- r.consts)
      refs.find(c.ty)
    for (f <- r.fields)
      refs.find(f.ty, addRCTHeader)

    val objcName = ident.name + (if (r.ext.objc) "_base" else "")
    val noBaseSelf = marshal.typename(ident, r) // Used for constant names
    val objcInterface = marshal.typename(objcName, r)
    val self = spec.reactNativeTypePrefix + objcInterface
    val fileName = spec.reactNativeTypePrefix + marshal.headerName(ident)

    refs.header.add("#import <Foundation/Foundation.h>")
    refs.header.add("#import <React/RCTBridgeModule.h>")
    refs.header.add("#import <React/RCTBridge.h>")

    val firstInitializerArg = if(r.fields.isEmpty) "" else IdentStyle.camelUpper("with_" + r.fields.head.ident.name)

    val hasOneFieldAsInterface = r.fields.filter(f => isExprInterface(f.ty.resolved) || isExprRecord(f.ty.resolved)).length > 0

    // Generate the header file for record
    writeObjcFile(fileName, origin, refs.header, w => {
      writeDoc(w, doc)
      w.wl(s"@interface $self : NSObject <RCTBridgeModule>")
      w.wl(s"@property (nonatomic, strong) NSMutableDictionary *objcImplementations;")
      if (hasOneFieldAsInterface) {
        w.wl(s"@property (nonatomic, strong) NSMutableDictionary *implementationsData;")
      }
      w.wl("@end")
    })

    // Generate the implementation file for record
    val implfileName = spec.reactNativeTypePrefix + marshal.typename(ident, r) + ".m"
    writeObjcFile(implfileName, origin, refs.body, w => {
      if (r.consts.nonEmpty) generateObjcConstants(w, r.consts, noBaseSelf, ObjcConstantType.ConstVariable)

      w.wl(s"""#import "$fileName"""")
      w.wl(s"""#import "${marshal.headerName(ident)}"""")
      w.wl
      w.wl(s"@implementation $self")
      w.wl

      w.wl("//Export module")
      w.wl(s"RCT_EXPORT_MODULE($self)")
      w.wl
      w.wl(s"@synthesize bridge = _bridge;")

      generateInitMethod(w, hasOneFieldAsInterface)
      //Avoid all warnings due to this method
      w.wl
      w.wl("+ (BOOL)requiresMainQueueSetup").braced {
        w.wl("return NO;")
      }

      //Release to remove objc instance from self.objcImplementations
      generateReleaseMethod(w, marshal.typename(ident, r))
      //Returns uid of all objc instances
      generateLogInstancesMethod(w)
      //Flush all objc intances from React Native Module's objcImplementations attribute
      generateFlushInstancesMethod(w)

      // Constructor from all fields (not copying)
      val init = s"RCT_REMAP_METHOD(init, init$firstInitializerArg"
      writeAlignedReactNativeCall(w, init, r.fields, "", f => {
        generateParams(f)
      })
      val begin = if(r.fields.length == 0) "WithResolver" else " withResolver"
      w.w(s"${begin}:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject)").braced {
        //Keep uuids of instances of interfaces and records

        if (hasOneFieldAsInterface) {
          w.wl(s"NSMutableDictionary *implementationsData = [[NSMutableDictionary alloc] init];")
        }
        var rejectCondition = ""
        r.fields.map(f => {
          val id = r.fields.indexOf(f)
          val isInterface = isExprInterface(f.ty.resolved)
          val isRecord = isExprRecord(f.ty.resolved)
          val fieldIdent = idObjc.field(f.ident)
          if (isInterface || isRecord) {
            fromReactType(f.ty.resolved, f.ident, s"field_$id", fieldIdent, w, "implementationsData")

          }
          //For reject condition
          val nullability = marshal.nullability(f.ty.resolved)
          if (!nullability.isDefined || nullability.get == "nonnull") {
            val additionalCondition = if (isInterface || isRecord) s"field_$id" else fieldIdent
            if (rejectCondition.length == 0) {
              rejectCondition.concat(s"!$additionalCondition")
            } else {
              rejectCondition.concat(s" || !$additionalCondition")
            }
          }
        })
        w.wl
        //Reject
        if (rejectCondition.length > 0) {
          w.wl(s"if ($rejectCondition)").braced {
            w.wl(s"""reject(@"impl_call_error", @"Error while calling $self::init", nil);""")
          }
        }
        w.wl
        //Resolve
        w.w(s"$objcInterface * finalResult = [[$objcInterface alloc] init$firstInitializerArg:")
        r.fields.map(f => {
          val id = r.fields.indexOf(f)
          val isInterface = isExprInterface(f.ty.resolved)
          val isRecord = isExprRecord(f.ty.resolved)
          if (id != 0) {
            w.w(s"${idObjc.field(f.ident)}:")
          }
          val arg = if (isInterface || isRecord) s"field_$id" else s"${idObjc.field(f.ident)}"
          w.w(arg)
          if (id != r.fields.length - 1) {
            w.w(" ")
          }

        })
        w.w("];")
        w.wl

        w.wl("NSString *uuid = [[NSUUID UUID] UUIDString];")
        val prefix = "RCT"
        val moduleName = if (self.indexOf(prefix) == 0) self.substring(prefix.length) else self
        w.wl(s"""$self *rctImpl = ($self *)[self.bridge moduleForName:@"$moduleName"];""")
        w.wl(s"[rctImpl.objcImplementations setObject:finalResult forKey:uuid];")
        w.wl(s"""NSDictionary *result = @{@"type" : @"$moduleName", @"uid" : uuid };""")
        w.wl("if (result)").braced {
          if (hasOneFieldAsInterface) {
              w.wl(s"""[self.implementationsData setObject:implementationsData forKey:uuid];""")
          }
          w.wl("resolve(result);")
        }
      }
      w.wl

      //Getters attributes
      def getterResult(ty: TypeRef, returnValue: String): String = {
        val tyType = marshal.paramType(ty)
        val findIntType = """int\d+_t""".r
        findIntType.findFirstIn(tyType) match {
          case Some(_) => s"@((int)$returnValue)"
          case None => {
            if (isEnum(ty.resolved)) {
              s"@((int)$returnValue)"
            } else if (tyType.equals("BOOL")) {
              s"@($returnValue)"
            } else {
              returnValue
            }
          }
        }
      }

      r.fields.map(f => {
        val id = r.fields.indexOf(f)
        val isFieldInterface = isExprInterface(f.ty.resolved)
        val isFieldRecord = isExprRecord(f.ty.resolved)
        val fieldIdent = idObjc.field(f.ident)
        val suffix = fieldIdent.substring(0, 1).toUpperCase() + fieldIdent.substring(1)
        //Getter
        val getterName = s"get$suffix"
        val fieldDecl = generateParams(f) match {
          case Some((ident, decl)) => decl
          case None => ""
        }
        w.wl(s"RCT_REMAP_METHOD($getterName, $getterName:(NSDictionary *)currentInstance withResolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)rejecter)").braced {

          val fieldTypeName = marshal.typename(f.ty.resolved)
          val objcFieldType = getRCTName(fieldTypeName)
          val reactFieldType = spec.reactNativeTypePrefix + objcFieldType
          w.wl(s"""$objcInterface *objcImpl = ($objcInterface *)[self.objcImplementations objectForKey:currentInstance[@"uid"]];""")

          val fieldType = marshal.paramType(f.ty)
          if (isFieldInterface || isFieldRecord) {
            w.wl("""NSDictionary *data = (NSDictionary *)[self.implementationsData objectForKey:currentInstance[@"uid"]];""")
            w.wl(s"""NSString *returnUuid = [data objectForKey:@"$fieldIdent"];""")
            val prefix = "RCT"
            val moduleFieldName = if (reactFieldType.indexOf(prefix) == 0) reactFieldType.substring(prefix.length) else reactFieldType
            w.wl(s"""NSDictionary *result = @{@"type" : @"$moduleFieldName", @"uid" : returnUuid };""")
          } else {

            val returnValue = s"objcImpl.$fieldIdent${if (isBinary(f.ty.resolved)) s".description" else ""}"
            w.wl(s"""NSDictionary *result = @{@"value" : ${getterResult(f.ty, returnValue)}};""")
          }

          w.wl(s"resolve(result);")
        }
        w.wl
      })

      w.wl("@end")
    })
  }

  override def writeObjcFile(fileName: String, origin: String, refs: Iterable[String], f: IndentWriter => Unit) {
    createFile(spec.reactNativeObjcOutFolder.get, fileName, (w: IndentWriter) => {
      w.wl("// AUTOGENERATED FILE - DO NOT MODIFY!")
      w.wl("// This file generated by Djinni from " + origin)
      w.wl
      if (refs.nonEmpty) {
        // Ignore the ! in front of each line; used to put own headers to the top
        // according to Objective-C style guide
        refs.foreach(s => w.wl(if (s.charAt(0) == '!') s.substring(1) else s))
        w.wl
      }
      f(w)
    })
  }

  def writeAlignedReactNativeCall(w: IndentWriter, call: String, params: Seq[Field], end: String, f: Field => Option[(String, String)]) = {
    w.w(call)
    val skipFirst = new SkipFirst
    params.foreach(p => {
      f(p) match {
        case Some((name, value)) =>
          skipFirst { w.wl; w.w(" " * math.max(0, call.length() - name.length)); w.w(name)  }
          w.w(":" + value)
        case _ =>
      }
    })
    w.w(end)
  }
}