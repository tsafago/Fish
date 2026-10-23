$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$upstream = Join-Path $root 'vendor\tn5250j\src\org\tn5250j'
$output = Join-Path $root 'build\generated\org\tn5250j'
New-Item -ItemType Directory -Force $output | Out-Null
foreach ($folder in @('framework\tn5250','encoding')) {
    $dest = Join-Path $output $folder
    New-Item -ItemType Directory -Force $dest | Out-Null
    Copy-Item "$upstream\$folder\*" $dest -Recurse -Force
}
foreach ($file in @('TN5250jConstants.java','keyboard\KeyMnemonic.java','keyboard\KeyMnemonicResolver.java',
    'event\ScreenListener.java','event\ScreenOIAListener.java','tools\logging\TN5250jLogger.java')) {
    $dest = Join-Path $output $file
    New-Item -ItemType Directory -Force (Split-Path $dest -Parent) | Out-Null
    Copy-Item "$upstream\$file" $dest -Force
}
# These audited build-time changes keep the pinned upstream tree unmodified.
function Replace-Exact([string]$path, [string]$before, [string]$after) {
    $text = [IO.File]::ReadAllText($path).Replace("`r`n","`n")
    $before = $before.Replace("`r`n","`n")
    if (-not $text.Contains($before)) { throw "Core patch anchor missing: $path -- $before" }
    [IO.File]::WriteAllText($path, $text.Replace($before,$after.Replace("`r`n","`n")), [Text.UTF8Encoding]::new($false))
}
$vt = Join-Path $output 'framework\tn5250\tnvt.java'
$entry = [IO.File]::ReadAllText((Join-Path $root 'src\core-adapter\accept-record.txt'))
Replace-Exact $vt "    public void run() {`n`n        if (enhanced)" ($entry + "`n    public void run() {`n`n        if (enhanced)")
Replace-Exact $vt '                    strpccmd();' '                    throw new IllegalStateException("Host STRPCCMD execution is disabled.");'
Replace-Exact $vt '                        error = sfParser.parseWriteToDisplayStructuredField(seg);' '                        error = rejectUnsupportedDisplayField();'
Replace-Exact $vt @'
                        int j = (bk.getNextByte() & 0xff) << 8 | bk.getNextByte()
                                & 0xff; // length
                        break;
'@ @'
                        int j = (bk.getNextByte() & 0xff) << 8 | bk.getNextByte()
                                & 0xff;
                        if (j > bk.streamSize - bk.getCurrentPos()
                                || j > screen52.getScreenLength() - screen52.getLastPos())
                            throw new IllegalStateException("Transparent Data exceeds the record or screen.");
                        while (j-- > 0) {
                            int td = bk.getNextByte() & 0xff;
                            screen52.setChar(codePage.ebcdic2uni(td));
                        }
                        break;
'@
Replace-Exact $vt @'
                        bk.getNextByte();
                        bk.getNextByte();
                        break;

                    case 19: // IC
'@ @'
                        throw new IllegalStateException("Unsupported WEA extended attribute; session stopped.");

                    case 19: // IC
'@
Replace-Exact $vt 'log.info("invalid " + from + " command " + os' 'log.warn("Host request rejected: " + from + " command " + os'
Replace-Exact $vt @'
            default:
                                log.debug("invalid structured field sub command "
'@ @'
            default:
                                log.warn("Unsupported structured field sub command "
'@
# Structured fields are processed strictly; unknown fields must not disappear.
Replace-Exact $vt 'log.debug("invalid structured field command "' 'log.warn("Unsupported structured field command "'
Replace-Exact $vt @'
        } catch (Exception e) {
        }
        ;

    }

    private final void writeErrorCode()
'@ @'
        } catch (Exception e) {
            throw new IllegalStateException("Malformed or unsupported structured field", e);
        }
        ;

    }

    private final void writeErrorCode()
'@
Replace-Exact $vt 'abyte0[53] = 0x7;' 'abyte0[53] = 0x0;'
Replace-Exact $vt 'abyte0[54] = 8;' 'abyte0[54] = 0;'
Replace-Exact $vt 'abyte0[4] = 64;' 'abyte0[4] = 58;'
Replace-Exact $vt 'abyte0[50] = 17;' 'abyte0[50] = 0x31;'
Replace-Exact $vt "writeGDS(0, 0, abyte0); // now tell them about us" "writeGDS(0, 0, Arrays.copyOf(abyte0, 61)); // RFC 1205 basic capabilities only"
$text = [IO.File]::ReadAllText($vt)
$start = $text.IndexOf('    private void writeStructuredField() {')
$end = $text.IndexOf('    private final void writeErrorCode()', $start)
if ($start -lt 0 -or $end -lt 0) { throw 'Query parser patch anchors missing.' }
$query = @'
    private void writeStructuredField() {
        int length = (bk.getNextByte() & 255) << 8 | bk.getNextByte() & 255;
        if (length != 5 || (bk.getNextByte() & 255) != 0xd9
                || (bk.getNextByte() & 255) != 0x70 || bk.getNextByte() != 0)
            throw new IllegalStateException("Unsupported or malformed Write Structured Field.");
        try { sendQueryResponse(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Query reply failed", e); }
    }

'@
[IO.File]::WriteAllText($vt, $text.Substring(0,$start) + $query + $text.Substring($end), [Text.UTF8Encoding]::new($false))
$planes = Join-Path $output 'framework\tn5250\ScreenPlanes.java'
Replace-Exact $planes 'import org.tn5250j.ExternalProgramConfig;' ''
Replace-Exact $planes 'ExternalProgramConfig.getInstance().getEtnPgmProps()' 'new Properties()'
Replace-Exact $planes 'screenExtended[pos] = (char) (ul | cs | nd);' 'screenExtended[pos] = (char) (ul | cs | nd | ((attr == 0x2a || attr == 0x2b || attr == 0x2e) ? EXTENDED_5250_BLINK : 0));'
$keys = Join-Path $output 'keyboard\KeyMnemonicResolver.java'
Replace-Exact $keys 'import org.tn5250j.tools.LangTool;' ''
Replace-Exact $keys 'LangTool.getString("key." + mnemonic.mnemonic)' 'mnemonic.name()'
$factory = Join-Path $output 'encoding\BuiltInCodePageFactory.java'
Replace-Exact $factory '        clazzes.add(CCSID930.class);' ''
$dbcs = Join-Path $output 'encoding\builtin\CCSID930.java'
if (Test-Path $dbcs) { Remove-Item -LiteralPath $dbcs }
$mapping = Join-Path $output 'encoding\CharMappings.java'
Replace-Exact $mapping 'return BuiltInCodePageFactory.getInstance().getCodePage(DFT_ENC);' 'throw new IllegalArgumentException("Unsupported code page: " + encoding);'
$screen = Join-Path $output 'framework\tn5250\Screen5250.java'
Replace-Exact $screen '    private boolean resetRequired = true;' "    private boolean prehelpError;`n    private boolean resetRequired = true;"
Replace-Exact $screen @'
                                   boolean unlockIfLocked) {
'@ @'
                                   boolean unlockIfLocked) {
        prehelpError = setErrorCode;
'@
Replace-Exact $screen 'return oia.getLevel() == ScreenOIA.OIA_LEVEL_INPUT_ERROR;' 'return prehelpError;'
Replace-Exact $screen @'
    protected void clearAll() {
'@ @'
    protected void clearAll() {
        prehelpError = false;
'@
Replace-Exact $screen @'
                            org.tn5250j.tools.system.OperatingSystem.displayURL(eb
                                    .toString());
'@ '                            log.warn("Host browser launch is disabled.");'
$constants = Join-Path $output 'TN5250jConstants.java'
Replace-Exact $constants '//    byte CMD_READ_MDT_FIELDS_ALT = (byte)0x82;' '    byte CMD_READ_MDT_FIELDS_ALT = (byte)0x82;'
Replace-Exact $vt '                    case CMD_READ_MDT_FIELDS: // 0x52 82 read MDT Fields' "                    case CMD_READ_MDT_FIELDS_ALT:`n                    case CMD_READ_MDT_FIELDS: // 0x52 82 read MDT Fields"
Replace-Exact $vt 'writeGDS(0, 3, sc.toByteArray());' 'writeGDS(0, 4, sc.toByteArray());'
Replace-Exact $vt 'writeGDS(0, 3, baosp.toByteArray());' 'writeGDS(0, 0, baosp.toByteArray());'
Replace-Exact $vt @'
        pendingUnlock = false;

        screen52.getScreenFields().readFormatTable(baosp, readType, codePage);
'@ @'
        pendingUnlock = false;
        baosp.write(screen52.getCurrentRow());
        baosp.write(screen52.getCurrentCol());
        baosp.write(0);

        screen52.getScreenFields().readFormatTable(baosp, readType, codePage);
'@
$stream = Join-Path $output 'framework\tn5250\Stream5250.java'
Replace-Exact $stream 'dataStart = 6 + abyte0[6];' 'dataStart = 6 + (abyte0[6] & 255);'
Replace-Exact $stream 'buffer == null || pos > buffer.length' 'buffer == null || pos >= buffer.length'
Replace-Exact $vt 'abyte0[49] = 1;' 'abyte0[49] = 0x23;'
$device = @('3','4','7','7','F','C',' ')
for ($i = 0; $i -lt 7; $i++) {
    $original = @('5','2','5','1','0','1','1')[$i]
    $position = 30 + $i
    Replace-Exact $vt "abyte0[$position] = codePage.uni2ebcdic('$original');" "abyte0[$position] = codePage.uni2ebcdic('$($device[$i])');"
}
$fields = Join-Path $output 'framework\tn5250\ScreenFields.java'
Replace-Exact $fields 'import static org.tn5250j.TN5250jConstants.CMD_READ_MDT_FIELDS;' "import static org.tn5250j.TN5250jConstants.CMD_READ_MDT_FIELDS;`nimport static org.tn5250j.TN5250jConstants.CMD_READ_MDT_FIELDS_ALT;"
Replace-Exact $fields 'readType == CMD_READ_MDT_FIELDS ||' 'readType == CMD_READ_MDT_FIELDS || readType == CMD_READ_MDT_FIELDS_ALT ||'
Replace-Exact $fields @'
                                        if (c == 0x1C)
                                            baosp.write(c);
'@ @'
                                        if (c == 0x1C || (c == 0 && (readType == CMD_READ_MDT_FIELDS_ALT
                                                || readType == CMD_READ_MDT_IMMEDIATE_ALT)))
                                            baosp.write(c);
'@
