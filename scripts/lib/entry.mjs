// Thin, importable CLI entry logic for the P1-10 verification script.
//
// Kept separate so the real orchestration result can be tested: a verification
// that returns ok:false (for example a failed cleanup) must never print the
// success line and must exit non-zero.

export async function runEntry({
  run,
  log = (message) => console.log(message),
  error = (message) => console.error(message),
}) {
  let result;
  try {
    result = await run();
  } catch (caught) {
    error(`VERIFICATION FAILED: ${caught instanceof Error ? caught.message : String(caught)}`);
    return { ok: false, exitCode: 1 };
  }
  if (!result || result.ok !== true) {
    const detail =
      result && Array.isArray(result.cleanupErrors) && result.cleanupErrors.length > 0
        ? `cleanup failed: ${result.cleanupErrors.join('; ')}`
        : 'verification did not complete successfully';
    error(`VERIFICATION FAILED: ${detail}`);
    return { ok: false, exitCode: 1 };
  }
  log('ALL CHECKS PASSED');
  return { ok: true, exitCode: 0 };
}
