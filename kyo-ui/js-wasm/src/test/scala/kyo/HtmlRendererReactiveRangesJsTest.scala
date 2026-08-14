package kyo

import kyo.internal.HtmlRenderer
import org.scalajs.dom
import scala.scalajs.js as scalajs

class HtmlRendererReactiveRangesJsTest extends kyo.test.Test[Any]:

    DomTestEnv.install

    override def config = super.config.sequential

    private val hooks =
        """var __focusReturnStack=[];
          |function faEnterPaths(){return {};}
          |function focusAutoPaths(){return {};}
          |function kyoLeavePrepare(){return [];}
          |function kyoEnterSeed(){}
          |function applyJsProps(root){var all=[root],desc=root.querySelectorAll("*");for(var i=0;i<desc.length;i++)all.push(desc[i]);
          |  for(var i=0;i<all.length;i++){var names=all[i].getAttributeNames();for(var j=0;j<names.length;j++){var name=names[j];
          |    if(name.indexOf("data-kyo-prop-")===0){all[i][name.slice(14)]=all[i].getAttribute(name);all[i].removeAttribute(name);}}}}
          |function ba(){}
          |function kyoSpawnGhosts(){}
          |function sweepFocusAuto(){}
          |function kyoSetCaret(target,start,end){target.setSelectionRange(start,end);}""".stripMargin

    private def install(html: String): Unit =
        dom.document.body.innerHTML = html
        discard(scalajs.Dynamic.global.eval(hooks + "\n" + HtmlRenderer.reactiveRangesJs))

    private def evalString(source: String): String =
        scalajs.Dynamic.global.eval(source).asInstanceOf[String]

    "embedded registry replaces table rows with contextual parsing and retains anchors" in {
        install("<table><tbody><!--kyo-rs:r--><tr id='old'><td>old</td></tr><!--kyo-re:r--></tbody></table>")
        discard(scalajs.Dynamic.global.eval("kyoRangeReplace('r',\"<tr id='new'><td>new</td></tr>\")"))
        val tbody = dom.document.querySelector("tbody")
        assert(tbody.children.length == 1)
        assert(tbody.children(0).asInstanceOf[dom.Element].tagName == "TR")
        assert(dom.document.getElementById("new") != null)
        assert(evalString("String(__kyoRanges.size)") == "1")
    }

    "embedded registry preserves authored tbody attributes across row category transitions" in {
        val id = "r000000010031"
        install(
            s"<table><tbody data-kyo-range-host='$id'><!--kyo-rs:$id--><tr><td>row</td></tr><!--kyo-re:$id--></tbody></table>"
        )
        discard(scalajs.Dynamic.global.eval(
            s"kyoRangeReplace('$id',\"<tbody id='authored' data-state='kept'><tr id='section-row'><td>section</td></tr></tbody>\")"
        ))
        val authored = dom.document.querySelector("table > tbody")
        assert(authored.id == "authored")
        assert(authored.getAttribute("data-state") == "kept")
        discard(scalajs.Dynamic.global.eval(
            s"kyoRangeReplace('$id',\"<tbody data-kyo-range-host='$id'><tr id='returned'><td>row</td></tr></tbody>\")"
        ))
        val returned = dom.document.querySelector("table > tbody")
        assert(returned.id == "")
        assert(!returned.hasAttribute("data-state"))
        assert(returned.getAttribute("data-kyo-range-host") == id)
        assert(returned.querySelector("#returned") != null)
    }

    "embedded registry moves table anchors around multiple authored sections and back" in {
        val id = "r000000010031"
        install(
            s"<table><tbody data-kyo-range-host='$id'><!--kyo-rs:$id--><tr><td>row</td></tr><!--kyo-re:$id--></tbody></table>"
        )
        discard(scalajs.Dynamic.global.eval(
            s"kyoRangeReplace('$id',\"<tbody id='first'></tbody><tbody id='second'></tbody>\")"
        ))
        assert(dom.document.querySelectorAll("table > tbody").length == 2)
        discard(scalajs.Dynamic.global.eval(
            s"kyoRangeReplace('$id',\"<tbody data-kyo-range-host='$id'><tr id='returned'></tr></tbody>\")"
        ))
        assert(dom.document.querySelectorAll("table > tbody").length == 1)
        assert(dom.document.querySelector("table > tbody #returned") != null)
    }

    "embedded registry rejects malformed and unknown ids without mutation" in {
        install("<!--kyo-rs:r--><span id='kept'>kept</span><!--kyo-re:r-->")
        val before    = dom.document.body.innerHTML
        val malformed = evalString("try{kyoRangeReplace('r1','changed');'no error'}catch(e){e.message}")
        val unknown = evalString(
            "try{kyoRangeReplace('r000000010078','changed');'no error'}catch(e){e.message}"
        )
        assert(malformed.contains("malformed replacement id"))
        assert(unknown.contains("unknown id"))
        assert(dom.document.body.innerHTML == before)
    }

    "embedded registry rejects corrupted live anchors without mutation" in {
        val id = "r000000010031"
        install(s"<!--kyo-rs:$id--><span id='kept'>kept</span><!--kyo-re:$id-->")
        discard(scalajs.Dynamic.global.eval(s"__kyoRanges.get('$id').end.data='kyo-re:r000000010032'"))
        val before = dom.document.body.innerHTML
        val error  = evalString(s"try{kyoRangeReplace('$id','changed');'no error'}catch(e){e.message}")
        assert(error.contains("markers are corrupted"))
        assert(dom.document.body.innerHTML == before)
    }

    "embedded range replacement restores raw focus and caret without a data path" in {
        val id = "r000000010031"
        install(s"<!--kyo-rs:$id--><div><input id='raw' value='one'></div><!--kyo-re:$id-->")
        discard(scalajs.Dynamic.global.eval("document.getElementById('raw').focus();document.getElementById('raw').setSelectionRange(1,1)"))
        discard(scalajs.Dynamic.global.eval(
            s"kyoRangeReplace('$id',\"<div><input id='raw' value='two'></div>\")"
        ))
        assert(dom.document.activeElement.id == "raw")
        assert(dom.document.activeElement.asInstanceOf[scalajs.Dynamic].selectionStart.asInstanceOf[Int] == 1)
    }

    "embedded table range restores raw focus relative to the reactive rows" in {
        val id = "r000000010031"
        install(
            s"<table><tbody data-kyo-range-host='$id'><!--kyo-rs:$id--><tr><td><input id='raw-table' value='one'></td></tr><!--kyo-re:$id--></tbody></table>"
        )
        discard(scalajs.Dynamic.global.eval(
            "document.getElementById('raw-table').focus();document.getElementById('raw-table').setSelectionRange(1,1)"
        ))
        discard(scalajs.Dynamic.global.eval(
            s"kyoRangeReplace('$id',\"<tbody data-kyo-range-host='$id'><tr><td><input id='raw-table' value='two'></td></tr></tbody>\")"
        ))
        assert(dom.document.activeElement.id == "raw-table")
        assert(dom.document.activeElement.asInstanceOf[scalajs.Dynamic].selectionStart.asInstanceOf[Int] == 1)
    }

    "embedded range replacement applies JS properties to a top-level element" in {
        val id = "r000000010031"
        install(s"<!--kyo-rs:$id--><!--kyo-re:$id-->")
        discard(scalajs.Dynamic.global.eval(
            s"kyoRangeReplace('$id',\"<input id='property' type='checkbox' data-kyo-prop-indeterminate='true'>\")"
        ))
        val property = dom.document.getElementById("property")
        assert(property.asInstanceOf[scalajs.Dynamic].indeterminate.asInstanceOf[Boolean])
        assert(!property.hasAttribute("data-kyo-prop-indeterminate"))
    }

end HtmlRendererReactiveRangesJsTest
