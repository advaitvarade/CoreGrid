import time
from pathlib import Path
print('Job A started', flush=True)
time.sleep(4)
Path('/job/output/result_a.txt').write_text('Job A finished successfully\n')
print('Job A finished', flush=True)
