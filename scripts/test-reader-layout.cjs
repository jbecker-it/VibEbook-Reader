// Execute the production injected script against an original layered comic in Chromium.
const { chromium } = require('playwright');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/java/de/folio/reader/ui/reader/ReaderScreen.kt', 'utf8');
const template = source.split('    return """')[1].split('    """.trimIndent()')[0];
function injection(fixed = null) {
    const values = {
        'preferences.leftHanded': 'false',
        'if (preferences.wideTapZones) "0.4" else "0.3"': '0.4',
        'jsString(colorRules)': JSON.stringify('html,body{color:#000!important;background:#fff!important;}'),
        'fixedLayout?.toString() ?: "null"': String(fixed),
        'preferences.margin': '24', 'preferences.fontSize': '32',
        'if (preferences.sansSerif) "sans-serif" else "serif"': 'serif',
        'preferences.lineHeight': '2.2',
    };
    return template.replace(/\$\{([^}]+)\}/g, (_, key) => {
        assert.ok(key in values, `Unknown Kotlin template value: ${key}`);
        return values[key];
    }).replace(/\$(twoPage|smoothTurns|colorScheme|restoreCharOffset|frac)\b/g,
        (_, key) => ({twoPage:'true',smoothTurns:'false',colorScheme:'dark',restoreCharOffset:'-1',frac:'0'})[key]);
}
const comic = `<!doctype html><html><head><meta name="viewport" content="width=1200,height=1800">
<style>body{margin:0;width:1200px;height:1800px;background:white;font:40px sans-serif}
#art{position:absolute;left:0;top:0;width:1200px;height:1800px}
#caption{position:absolute;left:240px;top:360px;width:400px;color:#123456}</style></head>
<body><svg id="art" viewBox="0 0 1200 1800"><rect width="1200" height="1800" fill="#eee"/>
<rect x="220" y="340" width="440" height="160" fill="white" stroke="black"/></svg>
<div id="caption">An original test comic</div></body></html>`;
(async () => {
    const browser = await chromium.launch({headless:true});
    try {
        const page = await browser.newPage();
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        async function load(html, fixed) {
            await page.goto('about:blank');
            await page.setContent(html);
            await page.evaluate(injection(fixed));
            await page.evaluate(() => __folio.layout());
        }
        for (const fixed of [null,true]) {
            await load(comic,fixed);
            for (const [width,height] of [[360,800],[1072,1448],[800,360]]) {
                await page.setViewportSize({width,height});
                await page.evaluate(() => __folio.layout());
                const g = await page.evaluate(() => {
                    const a = document.querySelector('#art').getBoundingClientRect();
                    const c = document.querySelector('#caption').getBoundingClientRect();
                    return {x:a.x,y:a.y,w:a.width,h:a.height,cx:c.x,cy:c.y,
                        font:getComputedStyle(document.body).fontSize,
                        color:getComputedStyle(document.querySelector('#caption')).color,screens:__folio.screens};
                });
                const scale = Math.min(width/1200,height/1800);
                assert.ok(Math.abs(g.w-1200*scale)<1);
                assert.ok(Math.abs(g.h-1800*scale)<1);
                assert.ok(Math.abs(g.cx-g.x-240*scale)<1);
                assert.ok(Math.abs(g.cy-g.y-360*scale)<1);
                assert.equal(g.font,'40px');
                assert.equal(g.color,'rgb(18, 52, 86)');
                assert.equal(g.screens,1);
                assert.ok(g.x>=-1 && g.y>=-1);
            }
            await page.evaluate(injection(fixed));
            assert.equal(await page.evaluate(() => getComputedStyle(document.body).fontSize),'40px');
            await page.evaluate(() => {
                window.events=[];
                window.AndroidReader={onPosition:(f,a)=>events.push(['position',f,a]),
                    onNextChapter:()=>events.push(['next']),onPrevChapter:()=>events.push(['prev'])};
                __folio.next(); __folio.prev();
            });
            assert.deepEqual(await page.evaluate(() => events),[['position',1,-1],['next'],['prev']]);
        }
        await load(comic.replace('width=1200,height=1800','width=device-width'),true);
        assert.equal(await page.evaluate(() => __folio.fixed),true);
        assert.equal(await page.evaluate(() => __folio.pageHeight),1800);
        // Legacy converted comics: no OPF hint or numeric viewport; a CSS-sized canvas with text overlays.
        const legacy = comic.replace('width=1200,height=1800','width=device-width')
            .replace('body{margin:0;width:1200px;height:1800px;background:white;font:40px sans-serif}',
                'body{margin:0} #page{position:relative;width:1200px;height:1800px;background:white;font:40px sans-serif}')
            .replace('<body>','<body><div id="page">').replace('</body>','</div></body>');
        for (const mode of [null,true]) {
            await page.setViewportSize({width:360,height:800});
            await load(legacy,mode);
            assert.equal(await page.evaluate(() => __folio.fixed),true);
            assert.equal(await page.evaluate(() => __folio.pageWidth),1200);
            assert.equal(await page.evaluate(() => __folio.pageHeight),1800);
            const g = await page.evaluate(() => {
                const a=document.querySelector('#art').getBoundingClientRect();
                const c=document.querySelector('#caption').getBoundingClientRect();
                return {w:a.width,h:a.height,dx:c.x-a.x,dy:c.y-a.y};
            });
            assert.ok(Math.abs(g.w-360)<1 && Math.abs(g.h-540)<1);
            assert.ok(Math.abs(g.dx-72)<1 && Math.abs(g.dy-108)<1);
        }
        // Synthetic reproduction of the supplied EPUB: 5x artwork and positioned text,
        // reduced by an author body transform. Do not clip the canvas before scaling.
        const scaledComic = `<!doctype html><html><head>
            <meta name="viewport" content="width=709,height=1066">
            <style>body{margin:0;width:709px;height:1066px;transform:rotate(0deg) scale(.2);transform-origin:0% 0%}
            #art{width:500%;height:500%} #caption{position:absolute;left:2000px;top:4500px;font:75px sans-serif}</style>
            </head><body><svg id="art" viewBox="0 0 3545 5330"><rect width="3545" height="5330" fill="silver"/></svg>
            <div id="caption">Original test caption</div></body></html>`;
        for (const mode of [null,true]) {
            await load(scaledComic,mode);
            for (const [width,height] of [[360,800],[1072,1448],[800,360]]) {
                await page.setViewportSize({width,height});
                await page.evaluate(injection(mode));
                await page.evaluate(() => __folio.layout());
                const g = await page.evaluate(() => {
                    const a=document.querySelector('#art').getBoundingClientRect();
                    const c=document.querySelector('#caption').getBoundingClientRect();
                    return {x:a.x,y:a.y,w:a.width,h:a.height,dx:c.x-a.x,dy:c.y-a.y,
                        overflow:getComputedStyle(document.body).overflow};
                });
                const scale=Math.min(width/709,height/1066);
                assert.ok(Math.abs(g.w-709*scale)<1 && Math.abs(g.h-1066*scale)<1,JSON.stringify(g));
                assert.ok(Math.abs(g.dx-400*scale)<1 && Math.abs(g.dy-900*scale)<1);
                assert.ok(g.x>=-1 && g.y>=-1 && g.x+g.w<=width+1 && g.y+g.h<=height+1);
                assert.equal(g.overflow,'visible');
            }
        }
        // An ordinary inline image with prose must not trigger fixed-page detection.
        await load('<html><body><svg width="300" height="400"></svg><p>Ordinary prose</p></body></html>',null);
        assert.equal(await page.evaluate(() => __folio.fixed),false);
        await load('<html><head><meta name="viewport" content="width=1200,height=1800"></head><body>' +
            '<p>This is an original paragraph for pagination regression testing.</p>'.repeat(150) + '</body></html>',false);
        assert.equal(await page.evaluate(() => __folio.fixed),false);
        assert.ok(await page.evaluate(() => __folio.screens>1));
        await page.evaluate(() => __folio.next());
        assert.equal(await page.evaluate(() => __folio.screen),1);
        assert.deepEqual(errors,[]);
        console.log('Passed: layered comic scaling, phone/Go 6/landscape, reinjection, navigation, novel pagination.');
    } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
