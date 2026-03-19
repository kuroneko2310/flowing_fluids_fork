# Partial visual regression removal (2026-03-17)

## 症状
- 水位がまとまって見えたり、離れて見えたり、`1 マスだけ空気` の穴が散発した。
- 特に薄い partial 水位が edge へ横移動する場面と、下へ落ちるときに細い柱を残す場面で見た目の不連続が強かった。

## 原因
- `MixinFlowingFluid` の partial 専用補助処理が、通常の fluid tick / equalizer / random tick と別テンポで働いていた。
- その結果、実際の水量変化より先に見た目だけ `1 ブロック丸移動` したり、落下後も source 側へ薄い残り水を無理に残してしまっていた。

## 対応
- `remainingAmount <= dropOff` の edge drift を停止した。
- 下方向への移動時に thin source を保持する partial 補助を削除した。
- これで partial は「その場の高さ表現」に寄せ、見た目を壊す補助移動はしない方針に戻した。

## 今後の方針
- partial 系を戻すなら、fluid tick / rendering / equalizer が同じ状態遷移を共有すると確認できた処理だけに限定する。
- `source を 0 にする横移動` や `落下後も source に薄い水を残す` 補助は、再導入しても別 feature flag で隔離する。
