// SPDX-License-Identifier: AGPL-3.0-only
'use strict';
const map=L.map('map',{zoomControl:true}).setView([31.2304,121.4737],16);
new ResizeObserver(()=>map.invalidateSize()).observe(document.getElementById('map'));
// AndroidView may first measure WebView at zero height; use its final native bounds.
window.resizeMap=height=>{document.getElementById('map').style.height=height+'px';map.invalidateSize();};
const tiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{
    maxZoom:19,attribution:'© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
}).addTo(map);
const notice=document.getElementById('tile-status');
const failedTiles=new Set();
tiles.on('tileerror',e=>{failedTiles.add(e.tile.src);notice.hidden=false;});
tiles.on('tileload',e=>{failedTiles.delete(e.tile.src);notice.hidden=failedTiles.size===0;});
tiles.on('tileunload',e=>{failedTiles.delete(e.tile.src);notice.hidden=failedTiles.size===0;});
const routeLayer=L.layerGroup().addTo(map);
let lastKey='', state={route:[],editable:true,mode:'point'}, selected, current;
map.on('click',e=>{if(state.editable&&!state.draw) NativeMap.tap(e.latlng.lat,e.latlng.lng);});
const surface=document.getElementById('map');
let stroke=null, preview=null, lastPixel=null, strokePointer=null;
surface.addEventListener('pointerdown',e=>{
    if(stroke||!state.editable||!state.draw||e.button!==0||e.target.closest('.leaflet-control'))return;
    e.preventDefault();e.stopPropagation();surface.setPointerCapture(e.pointerId);
    const p=map.mouseEventToContainerPoint(e),ll=map.containerPointToLatLng(p);
    stroke=[[ll.lat,ll.lng]];lastPixel=p;strokePointer=e.pointerId;
    preview=L.polyline(stroke,{color:'#ed7719',weight:4}).addTo(map);
},true);
surface.addEventListener('pointermove',e=>{
    if(!stroke||e.pointerId!==strokePointer)return;e.preventDefault();e.stopPropagation();
    const p=map.mouseEventToContainerPoint(e);if(p.distanceTo(lastPixel)<4||stroke.length>=2000)return;
    const ll=map.containerPointToLatLng(p);stroke.push([ll.lat,ll.lng]);lastPixel=p;preview.setLatLngs(stroke);
},true);
const endStroke=e=>{
    if(!stroke||e.pointerId!==strokePointer)return;e.preventDefault();e.stopPropagation();
    const data=stroke;stroke=null;strokePointer=null;map.removeLayer(preview);preview=null;
    if(surface.hasPointerCapture(e.pointerId))surface.releasePointerCapture(e.pointerId);
    if(e.type==='pointerup'&&state.editable&&state.draw&&data.length>1)NativeMap.stroke(JSON.stringify(data));
};
surface.addEventListener('pointerup',endStroke,true);
surface.addEventListener('pointercancel',endStroke,true);
window.renderState=s=>{
    state=s;
    if(s.draw&&s.editable){map.dragging.disable();map.doubleClickZoom.disable();map.touchZoom.disable();surface.style.touchAction='none';}
    else {map.dragging.enable();map.doubleClickZoom.enable();map.touchZoom.enable();surface.style.touchAction='';}
    if(stroke&&(!s.draw||!s.editable)){
        if(surface.hasPointerCapture(strokePointer))surface.releasePointerCapture(strokePointer);
        stroke=null;strokePointer=null;map.removeLayer(preview);preview=null;
    }
    const key=JSON.stringify([s.route,s.editable,s.mode]);
    if(key!==lastKey){
        lastKey=key;routeLayer.clearLayers();
        if(s.route.length>1)L.polyline(s.route,{color:'#3482ff',weight:5}).addTo(routeLayer);
        // Long hand-drawn paths need only endpoint markers, not thousands of DOM nodes.
        s.route.forEach((p,i)=>{
            if(s.mode==='live'&&i!==0&&i!==s.route.length-1)return;
            const icon=L.divIcon({className:'',iconSize:[30,30],iconAnchor:[15,15],html:`<div class="node ${i===0?'start':i===s.route.length-1?'end':''}">${i+1}</div>`});
            const marker=L.marker(p,{icon,draggable:s.editable&&s.mode==='route'}).addTo(routeLayer);
            marker.on('dragend',()=>{const p=marker.getLatLng();NativeMap.move(i,p.lat,p.lng);});
        });
    }
    if(selected)map.removeLayer(selected);
    selected=s.selected?L.circleMarker(s.selected,{radius:8,color:'#fff',weight:3,fillColor:'#3482ff',fillOpacity:1}).addTo(map):null;
    if(current)map.removeLayer(current);
    current=s.current?L.circleMarker(s.current,{radius:9,color:'#fff',weight:3,fillColor:'#ed7719',fillOpacity:1}).addTo(map):null;
};
window.fitRoute=()=>{if(state.route.length)map.fitBounds(L.latLngBounds(state.route),{padding:[35,35],maxZoom:18});else if(state.selected)map.setView(state.selected,16);};
window.centerPoint=(lat,lon)=>map.setView([lat,lon],17);
