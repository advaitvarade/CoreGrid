import time
from pathlib import Path
print('Job C started', flush=True)
time.sleep(2)
Path('/job/output/result_c.txt').write_text('Job C finished successfully\n')
print('Job C finished', flush=True)
