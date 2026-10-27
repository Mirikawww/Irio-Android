import { test } from 'node:test';
import assert from 'node:assert/strict';
import { auditSource } from './audit-text-colors.mjs';

const textImport = 'import com.composables.ui.components.Text\n';
test('detects missing foreground in a multiline error message', () => {
  const result = auditSource(textImport + 'Text(\n formError,\n modifier = Modifier.padding(12.dp),\n)');
  assert.equal(result.calls, 1);
  assert.equal(result.missing.length, 1);
  assert.equal(result.missing[0].line, 2);
});
test('nested colors and quoted color arguments do not hide a missing foreground', () => {
  for (const source of ['Text("color = red", modifier = Modifier.background(color = red))',
    'Text(error, style = TextStyle(color = red))', 'Text(error /* color = red */)']) {
    assert.equal(auditSource(textImport + source).missing.length, 1);
  }
});
test('explicit surface or inherited semantic foreground is accepted', () => {
  for (const color of ['Theme[colors][onPanelColor]', 'LocalContentColor.current', 'irioErrorTextColor()']) {
    assert.equal(auditSource(textImport + `Text(error, color = ${color})`).missing.length, 0);
  }
});
test('ignores comments, strings and non-UI Text constructors', () => {
  const result = auditSource(textImport + '// Text(error)\nval s = "Text(error)"\nTranscriptPiece.Text("body")');
  assert.equal(result.calls, 0);
});
test('recognizes aliased and fully qualified text APIs', () => {
  assert.equal(auditSource('import com.composables.ui.components.Text as Caption\nCaption(error)').missing.length, 1);
  assert.equal(auditSource('androidx.compose.material3.Text(error)').missing.length, 1);
  assert.equal(auditSource('com.composeunstyled.Text(error)').missing.length, 1);
});
test('BasicText requires foreground in its TextStyle or color argument', () => {
  const basicImport = 'import androidx.compose.foundation.text.BasicText\n';
  assert.equal(auditSource(basicImport + 'BasicText(emoji, style = TextStyle(fontSize = 16.sp))').missing.length, 1);
  assert.equal(auditSource(basicImport + 'BasicText(emoji, style = TextStyle(color = ink))').missing.length, 0);
});
test('outer color does not hide missing color in nested Text', () => {
  const result = auditSource(textImport + 'Text(build { Text(error) }, color = ink)');
  assert.equal(result.calls, 2);
  assert.equal(result.missing.length, 1);
});
