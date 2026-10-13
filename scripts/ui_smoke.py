"""Device-independent smoke checks against actual installed Android views."""
import subprocess, time, pathlib, xml.etree.ElementTree as ET, re
out=pathlib.Path('ui-checks'); out.mkdir(exist_ok=True)
def adb(*args):
    return subprocess.check_output(['adb',*args], text=True)
def nodes():
    adb('shell','uiautomator','dump','/sdcard/window.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/window.xml')).iter('node')
def node(text):
    return next((n for n in nodes() if n.get('text')==text),None)
def check(text):
    assert node(text) is not None, f'Missing view: {text}'
def tap(text):
    n=node(text)
    assert n is not None, f'Missing button: {text}'
    assert n.get('enabled')=='true', f'Disabled button: {text}'
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)); time.sleep(.8)
def shot(name):
    with (out/(name+'.png')).open('wb') as f:
        subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)
adb('logcat','-c')
adb('shell','am','start','-W','-n','com.lumisync.app/.MainActivity'); time.sleep(1)
check('Add your first light')
assert node('Turn on').get('enabled')=='false', 'Power must be disabled without a device'
shot('01-bluetooth-home')
tap('GE Cync'); check('Bring your Cync lights in'); shot('02-cync-setup')
tap('How to set up Home Assistant'); check('Set up Cync once'); shot('03-setup-help'); tap('Got it')
tap('Bluetooth'); check('Add your first light')
# Scroll the actual page to inspect the picker and use a named color.
adb('shell','input','swipe','500','1600','500','650','400'); time.sleep(.5)
if node('Coral') is None:
    adb('shell','input','swipe','500','1500','500','850','400'); time.sleep(.5)
tap('Coral'); shot('04-color-controls')
# Blank credentials should produce guidance, not a network request/crash.
tap('GE Cync')
adb('shell','input','swipe','500','1550','500','800','400'); time.sleep(.5)
tap('Connect my lights'); check('Enter an HTTPS server address, without a path.'); shot('05-validation')
log=adb('logcat','-d','-b','crash')
(out/'crash-log.txt').write_text(log)
assert 'FATAL EXCEPTION' not in log, log
(out/'result.txt').write_text('PASS: startup, disabled disconnected controls, navigation, help, color preset, setup validation, no Android crashes.\n')
print((out/'result.txt').read_text())
