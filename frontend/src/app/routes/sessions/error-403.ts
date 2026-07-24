import { Component } from '@angular/core';
import { ErrorCode } from '@shared/components/error-code/error-code';
import { TranslatePipe } from '@ngx-translate/core';

@Component({
  selector: 'app-error-403',
  template: `
    <error-code
      code="403"
      [title]="'errpage.e403_title' | translate"
      [message]="'errpage.e403_msg' | translate"
    />
  `,
  imports: [ErrorCode, TranslatePipe],
})
export class Error403 {}
