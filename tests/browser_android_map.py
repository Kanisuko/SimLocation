"""Android map interaction regression test with bundled assets and no tile traffic."""
from pathlib import Path

from playwright.sync_api import sync_playwright, expect


def main():
    root = Path(__file__).resolve().parents[1]
    assets = root / 'android/app/src/main/assets'
    with sync_playwright() as p:
        browser = p.chromium.launch(channel='msedge', headless=True)
        page = browser.new_page(viewport={'width':412, 'height':600})
        page.route('https://tile.openstreetmap.org/**', lambda request: request.abort())
        page.route('https://app.simlocation.local/**', lambda request: request.fulfill(
            path=assets / request.request.url.rsplit('/', 1)[-1]))
        taps, moves, strokes, errors = [], [], [], []
        page.expose_function('nativeTap', lambda lat, lon: taps.append((lat, lon)))
        page.expose_function('nativeMove', lambda index, lat, lon: moves.append((index, lat, lon)))
        page.expose_function('nativeStroke', lambda data: strokes.append(data))
        page.add_init_script('window.NativeMap={tap:(...a)=>nativeTap(...a),move:(...a)=>nativeMove(...a),stroke:nativeStroke}')
        page.on('pageerror', lambda ex: errors.append(str(ex)))
        page.goto('https://app.simlocation.local/map.html')
        page.evaluate('resizeMap(600)')
        expect(page.locator('#tile-status')).to_be_visible()
        data = dict(route=[[31.2304,121.4737],[31.2305,121.4737]],
                    selected=[31.2304,121.4737], current=None, editable=True, mode='route')
        page.evaluate('s=>{renderState(s);fitRoute()}', data)
        page.wait_for_timeout(400)
        expect(page.locator('.node')).to_have_count(2)
        page.mouse.click(80,150)
        page.wait_for_timeout(100)
        assert len(taps) == 1 and 31 <= taps[0][0] <= 32
        marker = page.locator('.leaflet-marker-icon').last.bounding_box()
        x, y = marker['x']+15, marker['y']+15
        page.mouse.move(x,y); page.mouse.down(); page.mouse.move(x+40,y+30,steps=10); page.mouse.up()
        page.wait_for_timeout(100)
        assert len(moves) == 1 and moves[0][0] == 1
        before_readonly = len(taps)
        data.update(editable=False,current=[31.23045,121.4737])
        page.evaluate('renderState', data)
        page.mouse.click(80,150)
        page.wait_for_timeout(100)
        assert len(taps) == before_readonly, (taps, before_readonly, page.evaluate('state'))
        assert page.evaluate('current.getLatLng().lat') == 31.23045
        assert not page.evaluate('routeLayer.getLayers().filter(l=>l.dragging).some(l=>l.dragging.enabled())')
        page.evaluate('resizeMap(350)')
        assert page.evaluate('map.getSize().y') == 350
        data.update(editable=True, mode='live', draw=True)
        page.evaluate('renderState', data)
        center = page.evaluate('map.getCenter()')
        page.mouse.move(100,140); page.mouse.down(); page.mouse.move(290,240,steps=30); page.mouse.up()
        page.wait_for_timeout(100)
        import json
        assert len(strokes) == 1 and len(json.loads(strokes[0])) > 10
        assert len(taps) == before_readonly
        assert page.evaluate('map.getCenter()') == center
        data.update(editable=False)
        page.evaluate('renderState', data)
        page.mouse.move(100,140); page.mouse.down(); page.mouse.move(200,230,steps=10); page.mouse.up()
        assert len(strokes) == 1
        assert not errors, errors
        browser.close()
    print('PASS: Android map clicks, drag, read-only playback, live drawing, marker, resize and tile failure')


if __name__ == '__main__':
    main()
